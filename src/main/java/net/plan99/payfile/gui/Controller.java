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

import com.google.bitcoin.core.*;
import com.google.bitcoin.protocols.channels.ValueOutOfRangeException;
import com.google.bitcoin.uri.BitcoinURI;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.*;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import net.plan99.payfile.client.PayFileClient;
import net.plan99.payfile.gui.utils.GuiUtils;

import javax.annotation.Nullable;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.Date;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static javafx.application.Platform.isFxApplicationThread;

/**
 * Gets created auto-magically by FXMLLoader via reflection. The widget fields are set to the GUI controls they're named
 * after. This class handles all the updates and event handling for the main UI.
 */
public class Controller {
    public ProgressBar syncProgress;
    public VBox syncBox;
    public HBox controlsBox;
    public Label requestMoneyLink;
    public Label balance;
    public Label progressBarLabel;
    public ContextMenu addressMenu;
    public HBox addressLabelBox;
    public Button sendMoneyOutBtn;
    public ImageView copyWidget;
    private Address primaryAddress;

    // PayFile specific stuff
    public Button downloadBtn;
    public ListView<String> filesList;
    private List<PayFileClient.File> files;
    @Nullable private PayFileClient.File selectedFile;

    // Called by FXMLLoader.
    public void initialize() {
        syncProgress.setProgress(-1);
        addressLabelBox.setOpacity(0.0);
        Tooltip tooltip = new Tooltip("Copy address to clipboard");
        Tooltip.install(copyWidget, tooltip);

        filesList.getSelectionModel().selectedIndexProperty().addListener((observableValue, prev, current) -> {
            final int index = current.intValue();
            selectedFile = index >= 0 ? files.get(index) : null;
            downloadBtn.setDisable(index < 0);
        });
    }

    public void onBitcoinSetup() {
        Main.bitcoin.wallet().addEventListener(new BalanceUpdater());
        primaryAddress = Main.bitcoin.wallet().getKeys().get(0).toAddress(Main.params);
        refreshBalanceLabel();
    }

    public void requestMoney(MouseEvent event) {
        // User clicked on the address.
        if (event.getButton() == MouseButton.SECONDARY || (event.getButton() == MouseButton.PRIMARY && event.isMetaDown())) {
            addressMenu.show(requestMoneyLink, event.getScreenX(), event.getScreenY());
        } else {
            String uri = getURI();
            System.out.println("Opening " + uri);
            try {
                Desktop.getDesktop().browse(URI.create(uri));
            } catch (IOException e) {
                // Couldn't open wallet app.
                GuiUtils.crashAlert(e);
            }
        }
    }

    private String getURI() {
        return BitcoinURI.convertToBitcoinURI(getAddress(), Utils.COIN, Main.APP_NAME, null);
    }

    private String getAddress() {
        return primaryAddress.toString();
    }

    public void copyWidgetClicked(MouseEvent event) {
        copyAddress(null);
    }

    public void copyAddress(ActionEvent event) {
        // User clicked icon or menu item.
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(getAddress());
        content.putHtml(format("<a href='%s'>%s</a>", getURI(), getAddress()));
        clipboard.setContent(content);
    }

    public void sendMoneyOut(ActionEvent event) {
        // Hide this UI and show the send money UI. This UI won't be clickable until the user dismisses send_money.
        Main.instance.overlayUI("send_money.fxml");
    }

    public void disconnect(ActionEvent event) {
        Main.client.disconnect();
        GuiUtils.fadeOut(Main.instance.mainUI);
        filesList.setItems(FXCollections.emptyObservableList());
        Main.instance.overlayUI("connect_server.fxml");
    }

    public void download(ActionEvent event) throws Exception {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select download directory");
        File directory = chooser.showDialog(Main.instance.mainWindow);
        if (directory == null)
            return;
        // The animation will start running once we return to the main loop, and the download will happen in the
        // background.
        final PayFileClient.File downloadingFile = checkNotNull(selectedFile);
        final File destination = new File(directory, downloadingFile.getFileName());
        syncProgress.setProgress(0.0);
        progressBarLabel.setText("Downloading " + downloadingFile.getFileName());
        final long startTime = System.currentTimeMillis();
        try {
            // Make an output stream that updates the GUI when data is written to it.
            FileOutputStream stream = new FileOutputStream(destination) {
                @Override
                public void write(byte[] b) throws IOException {
                    super.write(b);
                    final long bytesDownloaded = downloadingFile.getBytesDownloaded();
                    double done = bytesDownloaded / (double) downloadingFile.getSize();
                    Platform.runLater(() -> {
                        syncProgress.setProgress(done);
                        refreshBalanceLabel();
                    });
                }
            };
            final ListenableFuture<Void> future = Main.client.downloadFile(selectedFile, stream);
            Futures.addCallback(future, new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    int secondsTaken = (int) (System.currentTimeMillis() - startTime) / 1000;
                    GuiUtils.runAlert((stage, controller) -> controller.withOpenFile(stage, downloadingFile, destination, secondsTaken));
                    animateSwap();
                }

                @Override
                public void onFailure(Throwable t) {
                    GuiUtils.crashAlert(t);
                    animateSwap();
                }
            }, Platform::runLater);
            // Now we've started, swap in the progress bar with an animation.
            animateSwap();
        } catch (ValueOutOfRangeException e) {
            destination.delete();
            final String price = Utils.bitcoinValueToFriendlyString(BigInteger.valueOf(selectedFile.getPrice()));
            GuiUtils.informationalAlert("Insufficient funds",
                    format("This file costs %s BTC but you can't afford that. Try sending some money to this app first.", price));
        }
    }

    public void fileEntryClicked(MouseEvent event) throws Exception {
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            // Double click on a file: shortcut for downloading.
            download(null);
        }
    }

    public class ProgressBarUpdater extends DownloadListener {
        @Override
        protected void progress(double pct, int blocksSoFar, Date date) {
            super.progress(pct, blocksSoFar, date);
            Platform.runLater(() -> syncProgress.setProgress(pct / 100.0));
        }

        @Override
        protected void doneDownload() {
            super.doneDownload();
            Platform.runLater(Controller.this::readyToGoAnimation);
        }
    }

    public void readyToGoAnimation() {
        // Sync progress bar slides out ...
        TranslateTransition leave = new TranslateTransition(Duration.millis(600), syncBox);
        leave.setByY(80.0);
        // Buttons slide in and clickable address appears simultaneously.
        TranslateTransition arrive = new TranslateTransition(Duration.millis(600), controlsBox);
        arrive.setToY(0.0);
        requestMoneyLink.setText(primaryAddress.toString());
        FadeTransition reveal = new FadeTransition(Duration.millis(500), addressLabelBox);
        reveal.setToValue(1.0);
        ParallelTransition group = new ParallelTransition(arrive, reveal);
        // Slide out happens then slide in/fade happens.
        SequentialTransition both = new SequentialTransition(leave, group);
        both.setCycleCount(1);
        both.setInterpolator(Interpolator.EASE_BOTH);
        both.play();
    }

    private boolean controlsBoxOnScreen = true;

    public void animateSwap() {
        Node n1 = controlsBoxOnScreen ? controlsBox : syncBox;
        Node n2 = controlsBoxOnScreen ? syncBox : controlsBox;
        TranslateTransition leave = new TranslateTransition(Duration.millis(600), n1);
        leave.setByY(80.0);
        // Buttons slide in and clickable address appears simultaneously.
        TranslateTransition arrive = new TranslateTransition(Duration.millis(600), n2);
        arrive.setToY(0.0);
        SequentialTransition both = new SequentialTransition(leave, arrive);
        both.setCycleCount(1);
        both.setInterpolator(Interpolator.EASE_BOTH);
        both.play();
        controlsBoxOnScreen = !controlsBoxOnScreen;
    }

    public ProgressBarUpdater progressBarUpdater() {
        return new ProgressBarUpdater();
    }

    public class BalanceUpdater extends AbstractWalletEventListener {
        @Override
        public void onWalletChanged(Wallet wallet) {
            checkState(isFxApplicationThread());
            refreshBalanceLabel();
        }
    }

    public void refreshBalanceLabel() {
        BigInteger amount;
        if (Main.client != null)
            amount = Main.client.getRemainingBalance();
        else
            amount = Main.bitcoin.wallet().getBalance();
        balance.setText(Utils.bitcoinValueToFriendlyString(amount));
    }

    public void setFiles(List<PayFileClient.File> files) {
        this.files = files;
        List<String> names = Lists.transform(files, PayFileClient.File::getFileName);
        filesList.setItems(FXCollections.observableList(names));
    }
}
