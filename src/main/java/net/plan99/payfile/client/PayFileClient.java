package net.plan99.payfile.client;

import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.protocols.channels.PaymentChannelClient;
import com.google.bitcoin.protocols.channels.PaymentChannelCloseException;
import com.google.bitcoin.protocols.channels.StoredPaymentChannelClientStates;
import com.google.bitcoin.protocols.channels.ValueOutOfRangeException;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import net.plan99.payfile.Payfile;
import net.plan99.payfile.ProtocolException;
import org.bitcoin.paymentchannel.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.propagate;

public class PayFileClient {
    private static final Logger log = LoggerFactory.getLogger(PayFileClient.class);

    public static final int PORT = 18754;

    private final DataInputStream input;
    private final Socket socket;
    private final DataOutputStream output;
    private final Wallet wallet;
    private CompletableFuture<List<File>> currentQuery;
    private CompletableFuture currentFuture;
    private int chunkSize;
    private List<File> currentDownloads = new CopyOnWriteArrayList<>();
    private PaymentChannelClient paymentChannelClient;
    private volatile boolean running;
    private Consumer<Long> onPaymentMade;

    private boolean settling;
    private CompletableFuture<Void> settlementFuture;

    public PayFileClient(Socket socket, Wallet wallet) {
        try {
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
            this.wallet = wallet;

            ClientThread thread = new ClientThread();
            thread.setName(socket.toString());
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            throw propagate(e);
        }
    }

    public void disconnect() {
        running = false;
        if (paymentChannelClient != null)
            paymentChannelClient.connectionClosed();
        try {
            input.close();
            output.close();
        } catch (IOException ignored) {}
    }

    public CompletableFuture<Void> settlePaymentChannel() {
        // Tell it to terminate the payment relationship and thus broadcast the micropayment transactions. We will
        // resume control in destroyConnection below.
        settling = true;
        currentFuture = settlementFuture = new CompletableFuture<Void>();
        if (paymentChannelClient == null) {
            // Have to connect first.
            return initializePayments().thenCompose((v) -> {
                paymentChannelClient.close();
                return settlementFuture;
            });
        } else {
            paymentChannelClient.close();
            return settlementFuture;
        }
    }

    /**
     * Returns balance of the wallet plus whatever is left in the current channel, i.e. how much money is spendable
     * after a clean disconnect.
     */
    public BigInteger getRemainingBalance() {
        final StoredPaymentChannelClientStates extension = StoredPaymentChannelClientStates.getFromWallet(wallet);
        checkNotNull(extension);
        BigInteger valueRefunded = extension.getBalanceForServer(getServerID());
        return wallet.getBalance().add(valueRefunded);
    }

    /**
     * Returns how much money is still stuck in a channel with the given server. Does NOT include wallet balance.
     */
    public static BigInteger getBalanceForServer(String serverName, int port, Wallet wallet) {
        final StoredPaymentChannelClientStates extension = StoredPaymentChannelClientStates.getFromWallet(wallet);
        checkNotNull(extension);
        return extension.getBalanceForServer(getServerID(serverName, port));
    }

    /**
     * Returns how long you have to wait until this channel will either be settled by the server, or can be auto-settled
     * by the client (us).
     */
    public static long getSecondsUntilExpiry(String serverName, int port, Wallet wallet) {
        final StoredPaymentChannelClientStates extension = StoredPaymentChannelClientStates.getFromWallet(wallet);
        checkNotNull(extension);
        return extension.getSecondsUntilExpiry(getServerID(serverName, port));
    }

    public void setOnPaymentMade(Consumer<Long> onPaymentMade) {
        this.onPaymentMade = onPaymentMade;
    }

    public class File {
        private String fileName;
        private String description;
        private int handle;
        private long size;
        private long pricePerChunk;

        private long bytesDownloaded;
        private long nextChunk;
        private OutputStream downloadStream;
        private CompletableFuture<Void> completionFuture;

        public File(String fileName, String description, int handle, long size, long pricePerChunk) {
            this.fileName = fileName;
            this.description = description;
            this.handle = handle;
            this.size = size;
            this.pricePerChunk = pricePerChunk;
        }

        @Override
        public String toString() {
            return getFileName();
        }

        public long getBytesDownloaded() {
            return bytesDownloaded;
        }

        public String getFileName() {
            return fileName;
        }

        public String getDescription() {
            return description;
        }

        public int getHandle() {
            return handle;
        }

        public long getSize() {
            return size;
        }

        public boolean isAffordable() {
            long totalPrice = getPrice();
            if (totalPrice == 0)
                return true;
            long balance = getRemainingBalance().longValue();
            return totalPrice <= balance;
        }

        public long getPrice() {
            return pricePerChunk * (size / chunkSize);
        }
    }

    public CompletableFuture<List<File>> queryFiles() {
        if (currentQuery != null)
            throw new IllegalStateException("Already running a query");
        CompletableFuture<List<File>> future = new CompletableFuture<>();
        currentFuture = currentQuery = future;
        final Payfile.QueryFiles.Builder queryFiles = Payfile.QueryFiles.newBuilder()
                .setUserAgent("Basic client v1.0")
                .setBitcoinNetwork(wallet.getParams().getId());
        final Payfile.PayFileMessage.Builder msg = Payfile.PayFileMessage.newBuilder()
                .setType(Payfile.PayFileMessage.Type.QUERY_FILES)
                .setQueryFiles(queryFiles);
        try {
            writeMessage(msg.build());
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public CompletableFuture<Void> downloadFile(File file, OutputStream outputStream) throws IOException, ValueOutOfRangeException {
        if (file.downloadStream != null)
            throw new IllegalStateException("Already downloading this file");
        file.downloadStream = outputStream;

        currentFuture = file.completionFuture = new CompletableFuture<>();
        file.completionFuture.whenComplete((v, exception) -> { if (exception != null) file.downloadStream = null; });

        // Set up payments and then start the download.
        if (file.getPrice() > 0) {
            if (!file.isAffordable())
                throw new ValueOutOfRangeException("Cannot afford this file");
            log.info("Price is {}, ensuring payments are initialised ... ", file.getPrice());
            initializePayments().handle((v, ex) -> {
                try {
                    if (ex == null) {
                        log.info("Payments initialised. Downloading file {} {}", file.getHandle(), file.getFileName());
                        currentDownloads.add(file);
                        downloadNextChunk(file);
                    } else {
                        currentFuture.completeExceptionally(ex);
                    }
                    return null;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            log.info("Downloading file {} {}", file.getHandle(), file.getFileName());
            currentDownloads.add(file);
            downloadNextChunk(file);
        }
        return file.completionFuture;
    }

    private CompletableFuture<Void> initializePayments() {
        if (paymentChannelClient != null)
            return CompletableFuture.completedFuture(null);
        log.info("{}: Init payments", socket);
        Sha256Hash serverID = getServerID();
        // Lock up our entire balance into the channel for this server, minus the reference tx fee.
        final BigInteger channelSize = wallet.getBalance().subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);

        final CompletableFuture<Void> future = new CompletableFuture<>();
        paymentChannelClient = new PaymentChannelClient(wallet, wallet.getKeys().get(0), channelSize,
                serverID, new PaymentChannelClient.ClientConnection() {
            @Override
            public void sendToServer(Protos.TwoWayChannelMessage paymentMsg) {
                Payfile.PayFileMessage msg = Payfile.PayFileMessage.newBuilder()
                        .setType(Payfile.PayFileMessage.Type.PAYMENT)
                        .setPayment(paymentMsg.toByteString())
                        .build();
                try {
                    writeMessage(msg);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void destroyConnection(PaymentChannelCloseException.CloseReason reason) {
                if (reason != PaymentChannelCloseException.CloseReason.CLIENT_REQUESTED_CLOSE) {
                    log.warn("{}: Payment channel terminating with reason {}", socket, reason);
                    if (reason == PaymentChannelCloseException.CloseReason.SERVER_REQUESTED_TOO_MUCH_VALUE) {
                        future.completeExceptionally(new ValueOutOfRangeException("Insufficient balance"));
                    } else {
                        if (currentFuture != null)
                            currentFuture.completeExceptionally(new PaymentChannelCloseException("Unexpected payment channel termination", reason));
                    }
                } else {
                    checkState(settling);
                    log.info("{}: Payment channel settled successfully.", socket);
                    settlementFuture.complete(null);
                }
                paymentChannelClient.connectionClosed();
                paymentChannelClient = null;
            }

            @Override
            public void channelOpen() {
                log.debug("{}: Payment channel negotiated", socket);
                future.complete(null);
            }
        });
        paymentChannelClient.connectionOpen();
        return future;
    }

    private Sha256Hash getServerID() {
        return getServerID(socket.getInetAddress().getHostName(), socket.getPort());
    }

    private static Sha256Hash getServerID(String host, int port) {
        return Sha256Hash.create(String.format("%s:%d", host, port).getBytes());
    }

    private void downloadNextChunk(File file) throws IOException {
        if (currentFuture.isCompletedExceptionally())
            return;
        // Write two messages, one after the other: add to our balance, then spend it.
        try {
            if (paymentChannelClient != null) {
                paymentChannelClient.incrementPayment(BigInteger.valueOf(file.pricePerChunk));
                if (onPaymentMade != null)
                    onPaymentMade.accept(file.pricePerChunk);
            }
        } catch (ValueOutOfRangeException e) {
            // We ran out of moneyzz???
            throw new RuntimeException(e);
        }
        Payfile.DownloadChunk.Builder downloadChunk = Payfile.DownloadChunk.newBuilder();
        downloadChunk.setHandle(file.getHandle());
        // For now do one chunk at a time, although the protocol allows for more.
        downloadChunk.setChunkId(file.nextChunk++);
        Payfile.PayFileMessage.Builder msg = Payfile.PayFileMessage.newBuilder();
        msg.setType(Payfile.PayFileMessage.Type.DOWNLOAD_CHUNK);
        msg.setDownloadChunk(downloadChunk);
        writeMessage(msg.build());
    }

    private void writeMessage(Payfile.PayFileMessage msg) throws IOException {
        byte[] bits = msg.toByteArray();
        output.writeInt(bits.length);
        output.write(bits);
    }

    private class ClientThread extends Thread {
        @Override
        public void run() {
            try {
                running = true;
                while (true) {
                    int len = input.readInt();
                    if (len < 0 || len > 1024*1024)
                        throw new ProtocolException("Server sent message that's too large: " + len);
                    byte[] bits = new byte[len];
                    input.readFully(bits);
                    Payfile.PayFileMessage msg = Payfile.PayFileMessage.parseFrom(bits);
                    handle(msg);
                }
            } catch (EOFException | SocketException e) {
                if (running)
                    e.printStackTrace();
            } catch (Throwable t) {
                // Server flagged an error.
                if (currentFuture != null)
                    currentFuture.completeExceptionally(t);
                else
                    t.printStackTrace();
            }
        }
    }

    private void handle(Payfile.PayFileMessage msg) throws ProtocolException, IOException {
        switch (msg.getType()) {
            case MANIFEST:
                handleManifest(msg.getManifest());
                break;
            case DATA:
                handleData(msg.getData());
                break;
            case ERROR:
                handleError(msg.getError());
                break;
            case PAYMENT:
                handlePayment(msg.getPayment());
                break;
            default:
                throw new ProtocolException("Unhandled message");
        }
    }

    private void handleError(Payfile.Error error) throws ProtocolException {
        ProtocolException.Code code;
        try {
            code = ProtocolException.Code.valueOf(error.getCode());
        } catch (IllegalArgumentException e) {
            log.error("{}: Unknown error code: {}", socket, error.getCode());
            code = ProtocolException.Code.GENERIC;
        }
        throw new ProtocolException(code, error.getExplanation());
    }

    private void handlePayment(ByteString payment) throws ProtocolException {
        try {
            Protos.TwoWayChannelMessage paymentMessage = Protos.TwoWayChannelMessage.parseFrom(payment);
            paymentChannelClient.receiveMessage(paymentMessage);
        } catch (InvalidProtocolBufferException e) {
            throw new ProtocolException("Could not parse payment message: " + e.getMessage());
        } catch (ValueOutOfRangeException e) {
            // This shouldn't happen as we shouldn't try to open a channel larger than what we can afford.
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private File handleToFile(int handle) {
        for (File file : currentDownloads) {
            if (file.getHandle() == handle) {
                return file;
            }
        }
        return null;
    }

    private void handleData(Payfile.Data data) throws IOException, ProtocolException {
        File file = handleToFile(data.getHandle());
        if (file == null)
            throw new ProtocolException("Unknown handle");
        if (data.getChunkId() == file.nextChunk - 1) {
            final byte[] bits = data.getData().toByteArray();
            file.bytesDownloaded += bits.length;
            file.downloadStream.write(bits);
            if ((data.getChunkId() + 1) * chunkSize >= file.getSize()) {
                // File is done.
                file.downloadStream.close();
                currentDownloads.remove(file);
                file.completionFuture.complete(null);
                currentFuture = null;
            } else {
                downloadNextChunk(file);
            }
        } else {
            throw new ProtocolException("Server sent wrong part of file");
        }
    }

    private void handleManifest(Payfile.Manifest manifest) throws ProtocolException {
        if (currentQuery == null)
            throw new ProtocolException("Got MANIFEST before QUERY_FILES");
        List<File> files = new ArrayList<>(manifest.getFilesCount());
        for (Payfile.File f : manifest.getFilesList()) {
            File file = new File(f.getFileName(), f.getDescription(), f.getHandle(), f.getSize(), f.getPricePerChunk());
            files.add(file);
        }
        chunkSize = manifest.getChunkSize();
        currentFuture = null;
        currentQuery.complete(files);
    }
}
