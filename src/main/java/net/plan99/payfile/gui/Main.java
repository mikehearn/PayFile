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

package net.plan99.payfile.gui;

import com.aquafx_project.AquaFx;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.kits.WalletAppKit;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.protocols.channels.StoredPaymentChannelClientStates;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.bitcoin.utils.Threading;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import net.plan99.payfile.client.PayFileClient;
import net.plan99.payfile.gui.utils.GuiUtils;
import net.plan99.payfile.gui.utils.TextFieldValidator;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;

// To do list:
// - Make payment channel closing work properly: stored channels should be deleted when the channel is closed.
// - Make the server disconnect and channel close when the "Send money out" button is pressed.
// - Render the files with the descriptions and prices as well.
// - Solve the Mac menubar issue. Port the Mac specific tweaks to wallet-template.
// - Write a test plan that exercises every reasonable path through the app and test it.
// - Get an Apple developer ID and a Windows codesigning cert.
// - Find a way to dual boot Windows on my laptop.
// - Build, sign and test native packages!

public class Main extends Application {
    public static String APP_NAME = "PayFile";

    public static NetworkParameters params = RegTestParams.get();
    public static WalletAppKit bitcoin;
    public static Main instance;
    public static PayFileClient client;

    private StackPane uiStack;
    public Pane mainUI;
    public Controller controller;
    public Stage mainWindow;

    @Override
    public void start(Stage mainWindow) throws Exception {
        instance = this;
        // Show the crash dialog for any exceptions that we don't handle and that hit the main loop.
        GuiUtils.handleCrashesOnThisThread();
        try {
            init(mainWindow);
        } catch (Throwable t) {
            // Nicer message for the case where the block store file is locked.
            if (Throwables.getRootCause(t) instanceof BlockStoreException) {
                GuiUtils.informationalAlert("Already running", "This application is already running and cannot be started twice.");
            } else {
                throw t;
            }
        }
    }

    private void init(Stage mainWindow) throws IOException {
        this.mainWindow = mainWindow;
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            AquaFx.style();
        }
        // Load the GUI. The Controller class will be automagically created and wired up.
        URL location = getClass().getResource("main.fxml");
        FXMLLoader loader = new FXMLLoader(location);
        mainUI = (Pane) loader.load();
        controller = loader.getController();
        // Configure the window with a StackPane so we can overlay things on top of the main UI.
        uiStack = new StackPane(mainUI);
        mainWindow.setTitle(APP_NAME);
        final Scene scene = new Scene(uiStack);
        TextFieldValidator.configureScene(scene);   // Add CSS that we need.
        mainWindow.setScene(scene);

        // Make log output concise.
        BriefLogFormatter.init();
        // Tell bitcoinj to run event handlers on the JavaFX UI thread. This keeps things simple and means
        // we cannot forget to switch threads when adding event handlers. Unfortunately, the DownloadListener
        // we give to the app kit is currently an exception and runs on a library thread. It'll get fixed in
        // a future version. Also note that this doesn't affect the default executor for ListenableFutures.
        // That must be specified each time.
        Threading.USER_THREAD = Platform::runLater;
        // Create the app kit. It won't do any heavyweight initialization until after we start it.
        bitcoin = new WalletAppKit(params, new File("."), APP_NAME) {
            @Override
            protected void addWalletExtensions() throws Exception {
                super.addWalletExtensions();
                wallet().addExtension(new StoredPaymentChannelClientStates(wallet(), peerGroup()));
            }
        };
        if (params == RegTestParams.get()) {
            bitcoin.connectToLocalHost();   // You should run a regtest mode bitcoind locally.
        } else if (params == MainNetParams.get()) {
            // Checkpoints are block headers that ship inside our app: for a new user, we pick the last header
            // in the checkpoints file and then download the rest from the network. It makes things much faster.
            // Checkpoint files are made using the BuildCheckpoints tool and usually we have to download the
            // last months worth or more (takes a few seconds).
            bitcoin.setCheckpoints(getClass().getResourceAsStream("checkpoints"));
        }

        // Now configure and start the appkit. This will take a second or two - we could show a temporary splash screen
        // or progress widget to keep the user engaged whilst we initialise, but we don't.
        bitcoin.setDownloadListener(controller.progressBarUpdater())
               .setBlockingStartup(false)
               .startAndWait();
        // Don't make the user wait for confirmations for now, as the intention is they're sending it their own money!
        bitcoin.wallet().allowSpendingUnconfirmedTransactions();
        System.out.println(bitcoin.wallet());
        controller.onBitcoinSetup();
        overlayUI("connect_server.fxml");
        mainUI.setVisible(false);
        mainWindow.show();
    }

    public class OverlayUI {
        Node ui;
        Object controller;

        public OverlayUI(Node ui, Object controller) {
            this.ui = ui;
            this.controller = controller;
        }

        public void done() {
            GuiUtils.fadeOutAndRemove(ui, uiStack);
            GuiUtils.blurIn(mainUI);
            this.ui = null;
            this.controller = null;
        }
    }

    /** Loads the FXML file with the given name, blurs out the main UI and puts this one on top. */
    public OverlayUI overlayUI(String name) {
        try {
            // Load the UI from disk.
            URL location = getClass().getResource(name);
            FXMLLoader loader = new FXMLLoader(location);
            Pane ui = (Pane) loader.load();
            Object controller = loader.getController();
            OverlayUI pair = new OverlayUI(ui, controller);
            // Auto-magically set the overlayUi member, if it's there.
            try {
                controller.getClass().getDeclaredField("overlayUi").set(controller, pair);
            } catch (IllegalAccessException | NoSuchFieldException ignored) {
            }
            GuiUtils.blurOut(mainUI);
            uiStack.getChildren().add(ui);
            GuiUtils.fadeIn(ui);
            return pair;
        } catch (IOException e) {
            throw new RuntimeException(e);  // Can't happen.
        }
    }

    /** Connect to the given server in the background and return the client object when done. */
    public ListenableFuture<PayFileClient> connect(String server) {
        final SettableFuture<PayFileClient> future = SettableFuture.create();
        new Thread("Connect to server thread") {
            @Override
            public void run() {
                try {
                    Socket socket = new Socket(server, PayFileClient.PORT);
                    PayFileClient client = new PayFileClient(socket, bitcoin.wallet());
                    future.set(client);
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        }.start();
        return future;
    }

    @Override
    public void stop() throws Exception {
        bitcoin.stopAndWait();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}