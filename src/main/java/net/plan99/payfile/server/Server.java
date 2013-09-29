/**
 * Author: Mike Hearn <mhearn@bitcoinfoundation.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.plan99.payfile.server;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.TransactionBroadcaster;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.kits.WalletAppKit;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.protocols.channels.PaymentChannelCloseException;
import com.google.bitcoin.protocols.channels.PaymentChannelServer;
import com.google.bitcoin.protocols.channels.StoredPaymentChannelServerStates;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import net.plan99.payfile.Payfile;
import net.plan99.payfile.ProtocolException;
import org.bitcoin.paymentchannel.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;

/**
 * An instance of Server handles one client. The static main method opens up a listening socket and starts a thread
 * that runs a new Server for each client that connects. This one thread per connection model is simple and
 * easy to understand, but for lots of clients you'd need to possibly minimise the stack size.
 */
public class Server implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private static final int CHUNK_SIZE = 1024*50;
    private static final int PORT = 18754;
    private static final int MIN_ACCEPTED_CHUNKS = 5;   // Require download of at least this many chunks.
    private static File directoryToServe;
    private static int defaultPricePerChunk;
    private static ArrayList<Payfile.File> manifest;
    private static final NetworkParameters PARAMS = RegTestParams.get();
    // The client socket that we're talking to.
    private final Socket socket;
    private final Wallet wallet;
    private final TransactionBroadcaster transactionBroadcaster;
    private final String peerName;
    private DataInputStream input;
    private DataOutputStream output;
    private PaymentChannelServer payments;
    private long balance;

    public Server(Wallet wallet, TransactionBroadcaster transactionBroadcaster, Socket socket) {
        this.socket = socket;
        this.peerName = socket.getInetAddress().getHostAddress();
        this.wallet = wallet;
        this.transactionBroadcaster = transactionBroadcaster;
    }

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();

        defaultPricePerChunk = 100;   // satoshis
        directoryToServe = new File("/tmp/xx");
        if (!buildFileList())
            return;

        WalletAppKit appkit = new WalletAppKit(PARAMS, new File("."), "payfile-server") {
            @Override
            protected void addWalletExtensions() throws Exception {
                super.addWalletExtensions();
                wallet().addExtension(new StoredPaymentChannelServerStates(wallet(), peerGroup()));
            }

            @Override
            protected void onSetupCompleted() {
                super.onSetupCompleted();
                peerGroup().setUserAgent("PayFile Server", "1.0");
            }
        };
        if (PARAMS == RegTestParams.get()) {
            appkit.connectToLocalHost();
        }
        appkit.setAutoSave(true);
        appkit.startAndWait();

        System.out.println(appkit.wallet().toString(false, true, true, appkit.chain()));

        ServerSocket socket = new ServerSocket(PORT);
        Socket clientSocket;
        do {
            clientSocket = socket.accept();
            final Server server = new Server(appkit.wallet(), appkit.peerGroup(), clientSocket);
            Thread clientThread = new Thread(server, clientSocket.toString());
            clientThread.start();
        } while (true);
    }

    private static boolean buildFileList() {
        final File[] files = directoryToServe.listFiles();
        if (files == null) {
            log.error("{} is not a directory", directoryToServe);
            return false;
        }
        manifest = new ArrayList<>();
        int counter = 0;
        for (File f : files) {
            Payfile.File file = Payfile.File.newBuilder()
                    .setFileName(f.getName())
                    .setDescription("Some cool file")
                    .setHandle(counter++)
                    .setSize(f.length())
                    .setPricePerChunk(defaultPricePerChunk)
                    .build();
            manifest.add(file);
        }
        if (counter == 0) {
            log.error("{} contains no files", directoryToServe);
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            log.info("Got new connection from {}", peerName);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());

            while (true) {
                int len = input.readInt();
                if (len < 0 || len > 64 * 1024) {
                    log.error("Client sent over-sized message of {} bytes", len);
                    return;
                }
                byte[] bits = new byte[len];
                input.readFully(bits);
                Payfile.PayFileMessage msg = Payfile.PayFileMessage.parseFrom(bits);
                handle(msg);
            }
        } catch (EOFException ignored) {
            log.info("Client {} disconnected", peerName);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ProtocolException e) {
            try {
                sendError(e);
            } catch (IOException ignored) {}
        } catch (Throwable t) {
            // Internal server error.
            try {
                sendError(new ProtocolException(ProtocolException.Code.INTERNAL_ERROR, "Internal server error: " + t.toString()));
            } catch (IOException ignored) {}
        } finally {
            forceClose();
        }
    }

    private void forceClose() {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private void sendError(ProtocolException e) throws IOException {
        Payfile.Error error = Payfile.Error.newBuilder()
                .setCode(e.getCode().name())
                .setExplanation(e.getMessage())
                .build();
        Payfile.PayFileMessage msg = Payfile.PayFileMessage.newBuilder()
                .setType(Payfile.PayFileMessage.Type.ERROR)
                .setError(error)
                .build();
        writeMessage(msg);
    }

    private void handle(Payfile.PayFileMessage msg) throws IOException, ProtocolException {
        switch (msg.getType()) {
            case QUERY_FILES:
                queryFiles(msg.getQueryFiles());
                break;
            case PAYMENT:
                payment(msg.getPayment());
                break;
            case DOWNLOAD_CHUNK:
                downloadChunk(msg.getDownloadChunk());
                break;
            default:
                throw new ProtocolException("Unknown message");
        }
    }

    private void queryFiles(Payfile.QueryFiles queryFiles) throws IOException, ProtocolException {
        log.info("{}: File query request from '{}'", peerName, queryFiles.getUserAgent());
        checkForNetworkMismatch(queryFiles);
        Payfile.Manifest manifestMsg = Payfile.Manifest.newBuilder()
                .addAllFiles(manifest)
                .setChunkSize(CHUNK_SIZE)
                .build();
        Payfile.PayFileMessage msg = Payfile.PayFileMessage.newBuilder()
                .setType(Payfile.PayFileMessage.Type.MANIFEST)
                .setManifest(manifestMsg)
                .build();
        writeMessage(msg);
    }

    private void checkForNetworkMismatch(Payfile.QueryFiles queryFiles) throws ProtocolException {
        final String theirNetwork = queryFiles.getBitcoinNetwork();
        final String myNetwork = wallet.getParams().getId();
        if (!theirNetwork.equals(myNetwork)) {
            final String msg = String.format("Client is using '%s' and server is '%s'", theirNetwork, myNetwork);
            throw new ProtocolException(ProtocolException.Code.NETWORK_MISMATCH, msg);
        }
    }

    private void writeMessage(Payfile.PayFileMessage msg) {
        try {
            byte[] bits = msg.toByteArray();
            output.writeInt(bits.length);
            output.write(bits);
        } catch (IOException e) {
            log.error("{}: Failed writing message: {}", peerName, e);
            forceClose();
        }
    }

    private void payment(ByteString payment) {
        try {
            maybeInitPayments();
            Protos.TwoWayChannelMessage msg = Protos.TwoWayChannelMessage.parseFrom(payment);
            payments.receiveMessage(msg);
        } catch (InvalidProtocolBufferException e) {
            log.error("{}: Got an unreadable payment message: {}", peerName, e);
            forceClose();
        }
    }

    private void maybeInitPayments() {
        if (payments == null) {
            BigInteger minPayment = BigInteger.valueOf(defaultPricePerChunk * MIN_ACCEPTED_CHUNKS);
            payments = new PaymentChannelServer(transactionBroadcaster, wallet, minPayment, new PaymentChannelServer.ServerConnection() {
                @Override
                public void sendToClient(Protos.TwoWayChannelMessage msg) {
                    Payfile.PayFileMessage.Builder m = Payfile.PayFileMessage.newBuilder();
                    m.setPayment(msg.toByteString());
                    m.setType(Payfile.PayFileMessage.Type.PAYMENT);
                    writeMessage(m.build());
                }

                @Override
                public void destroyConnection(PaymentChannelCloseException.CloseReason reason) {
                    if (reason != PaymentChannelCloseException.CloseReason.CLIENT_REQUESTED_CLOSE) {
                        log.error("{}: Payments terminated abnormally: {}", peerName, reason);
                    }
                    payments = null;
                }

                @Override
                public void channelOpen(Sha256Hash contractHash) {
                    log.info("{}: Payments negotiated: {}", peerName, contractHash);
                }

                @Override
                public void paymentIncrease(BigInteger by, BigInteger to) {
                    long byAmount = by.longValue();
                    Preconditions.checkArgument(byAmount > 0);
                    // TODO: Fix this once slf4j is upgraded.
                    log.info("{}: Increased balance by {} to {}", new Object[]{peerName, byAmount, balance});
                    balance += byAmount;
                }
            });
            payments.connectionOpen();
        }
    }

    private void downloadChunk(Payfile.DownloadChunk downloadChunk) throws ProtocolException {
        try {
            Payfile.File file = null;
            for (Payfile.File f : manifest) {
                if (f.getHandle() == downloadChunk.getHandle()) {
                    file = f;
                    break;
                }
            }
            if (file == null)
                throw new ProtocolException("DOWNLOAD_CHUNK specified invalid file handle " + downloadChunk.getHandle());
            if (downloadChunk.getNumChunks() <= 0)
                throw new ProtocolException("DOWNLOAD_CHUNK: num_chunks must be >= 1");
            if (file.getPricePerChunk() > 0) {
                // How many chunks can the client afford with their current balance?
                checkState(balance >= 0);
                long affordableChunks = balance / file.getPricePerChunk();
                if (affordableChunks < downloadChunk.getNumChunks())
                    throw new ProtocolException("Insufficient payment received for requested amount of data");
                balance -= downloadChunk.getNumChunks();
            }
            for (int i = 0; i < downloadChunk.getNumChunks(); i++) {
                long chunkId = downloadChunk.getChunkId() + i;
                if (chunkId == 0)
                    log.info("{}: Starting download of {}", peerName, file.getFileName());
                File diskFile = new File(directoryToServe, file.getFileName());
                FileInputStream fis = new FileInputStream(diskFile);
                final long offset = chunkId * CHUNK_SIZE;
                if (fis.skip(offset) != offset)
                    throw new IOException("Bogus seek");
                byte[] chunk = new byte[CHUNK_SIZE];
                final int bytesActuallyRead = fis.read(chunk);
                if (bytesActuallyRead < 0) {
                    log.error("Reached EOF");
                } else if (bytesActuallyRead > 0 && bytesActuallyRead < chunk.length) {
                    chunk = Arrays.copyOf(chunk, bytesActuallyRead);
                }
                Payfile.PayFileMessage msg = Payfile.PayFileMessage.newBuilder()
                        .setType(Payfile.PayFileMessage.Type.DATA)
                        .setData(Payfile.Data.newBuilder()
                                .setChunkId(downloadChunk.getChunkId())
                                .setHandle(file.getHandle())
                                .setData(ByteString.copyFrom(chunk))
                                .build()
                        ).build();
                writeMessage(msg);
            }
        } catch (IOException e) {
            throw new ProtocolException("Error reading from disk: " + e.getMessage());
        }
    }
}
