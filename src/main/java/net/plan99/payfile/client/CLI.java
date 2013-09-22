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

package net.plan99.payfile.client;

import asg.cliche.Command;
import asg.cliche.Param;
import asg.cliche.ShellFactory;
import com.google.bitcoin.core.AbstractWalletEventListener;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.kits.WalletAppKit;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.protocols.channels.StoredPaymentChannelClientStates;
import com.google.bitcoin.utils.BriefLogFormatter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.util.List;

public class CLI {
    public static final RegTestParams PARAMS = RegTestParams.get();

    private PayFileClient client;
    private List<PayFileClient.File> files;
    private WalletAppKit appkit;

    public CLI(Socket socket) throws IOException {
        appkit = new WalletAppKit(PARAMS, new File("."), "payfile-cli") {
            @Override
            protected void addWalletExtensions() throws Exception {
                super.addWalletExtensions();
                wallet().addExtension(new StoredPaymentChannelClientStates(wallet(), peerGroup()));
            }
        };
        appkit.connectToLocalHost().startAndWait();
        appkit.wallet().allowSpendingUnconfirmedTransactions();
        appkit.wallet().addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                System.out.println("Received money: " + Utils.bitcoinValueToFriendlyString(tx.getValueSentToMe(appkit.wallet())));
            }
        });
        System.out.println("Send coins to " + appkit.wallet().getKeys().get(0).toAddress(PARAMS));
        System.out.println("Your balance is " + Utils.bitcoinValueToFriendlyString(appkit.wallet().getBalance()));
        client = new PayFileClient(socket, appkit.wallet());
    }

    public void shutdown() {
        client.disconnect();
        appkit.stopAndWait();
    }

    @Command(description = "Show the files advertised by the remote server")
    public void ls() throws Exception {
        files = client.queryFiles().get();
        for (PayFileClient.File file : files) {
            String priceMessage = Utils.bitcoinValueToFriendlyString(BigInteger.valueOf(file.getPrice()));
            String affordability = file.isAffordable() ? "" : ", unaffordable";
            String str = String.format("%d)  [%d bytes, %s%s] \"%s\" :  %s", file.getHandle(),
                    file.getSize(), priceMessage, affordability, file.getFileName(), file.getDescription());
            System.out.println(str);
        }
    }

    @Command(description = "Download the given file ID to the given directory")
    public void get(
            @Param(name="handle", description="Numeric ID of the file") int handle,
            @Param(name="directory", description="Directory to save the file to") String directory) throws Exception {
        if (files == null) {
            System.out.println("Fetching file list ...");
            files = client.queryFiles().get();
        }
        File dir = new File(directory);
        if (!dir.isDirectory()) {
            System.out.println(directory + " is not a directory");
            return;
        }
        String fileName = null;
        PayFileClient.File serverFile = null;
        for (PayFileClient.File f : files) {
            if (f.getHandle() == handle) {
                fileName = f.getFileName();
                serverFile = f;
                break;
            }
        }
        if (serverFile == null) {
            System.out.println("Unknown file handle " + handle);
            return;
        }
        File output = new File(dir, fileName);
        final PayFileClient.File fServerFile = serverFile;
        FileOutputStream stream = new FileOutputStream(output) {
            @Override
            public void write(byte[] b) throws IOException {
                super.write(b);
                final long bytesDownloaded = fServerFile.getBytesDownloaded();
                double percentDone = bytesDownloaded / (double) fServerFile.getSize() * 100;
                System.out.println(String.format("Downloaded %d kilobytes [%.2f%% done]", bytesDownloaded / 1024, percentDone));
            }
        };
        client.downloadFile(serverFile, stream).get();
        System.out.println(String.format("Downloaded %s successfully.", fileName));
        System.out.println(String.format("You have %s remaining.", Utils.bitcoinValueToFriendlyString(client.getRemainingBalance())));
    }

    @Command(description = "Print info about your wallet")
    public void wallet() {
        System.out.println(appkit.wallet().toString(false, true, true, appkit.chain()));
        System.out.println("Total remaining: " + Utils.bitcoinValueToFriendlyString(client.getRemainingBalance()));
    }

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        //Logger.getLogger("").setLevel(Level.OFF);
        String server = args[0];
        System.out.println("Connecting to " + server);
        Socket socket = new Socket(server, 18754);
        final CLI cli = new CLI(socket);
        ShellFactory.createConsoleShell(server, "PayFile", cli).commandLoop();
        cli.shutdown();
    }
}
