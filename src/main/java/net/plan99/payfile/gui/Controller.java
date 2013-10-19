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
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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

import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static javafx.beans.binding.Bindings.isNull;
import static net.plan99.payfile.gui.utils.GuiUtils.*;

/**
 * Gets created auto-magically by FXMLLoader via reflection. The widget fields are set to the GUI controls they're named
 * after. This class handles all the updates and event handling for the main UI.
 */
public class Controller {
    public ProgressBar progressBar;
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
    public Button downloadBtn, cancelBtn;
    public ListView<PayFileClient.File> filesList;
    private ObservableList<PayFileClient.File> files;
    private ReadOnlyObjectProperty<PayFileClient.File> selectedFile;
    private CompletableFuture<Void> downloadFuture;

    // Called by FXMLLoader.
    public void initialize() {
        progressBar.setProgress(-1);
        addressLabelBox.setOpacity(0.0);
        Tooltip tooltip = new Tooltip("Copy address to clipboard");
        Tooltip.install(copyWidget, tooltip);

        cancelBtn.setVisible(false);

        // The PayFileClient.File.toString() method is good enough for rendering list cells for now.
        files = FXCollections.observableArrayList();
        filesList.setItems(files);
        selectedFile = filesList.getSelectionModel().selectedItemProperty();
        // Don't allow the user to press download unless an item is selected.
        downloadBtn.disableProperty().bind(isNull(selectedFile));
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
                crashAlert(e);
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
        // Free up the users money, if any is suspended in a payment channel for this server.
        Main.client.releasePaymentChannel();
        // Hide this UI and show the send money UI. This UI won't be clickable until the user dismisses send_money.
        Main.instance.overlayUI("send_money.fxml");
    }

    public void disconnect(ActionEvent event) {
        Main.client.disconnect();
        fadeOut(Main.instance.mainUI);
        files.clear();
        Main.instance.overlayUI("connect_server.fxml");
    }

    public void download(ActionEvent event) throws Exception {
        File destination = null;
        try {
            final PayFileClient.File downloadingFile = checkNotNull(selectedFile.get());
            if (downloadingFile.getPrice() > getBalance().longValue())
                throw new ValueOutOfRangeException("");
            // Ask the user where to put it.
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select download directory");
            File directory = chooser.showDialog(Main.instance.mainWindow);
            if (directory == null)
                return;
            destination = new File(directory, downloadingFile.getFileName());
            FileOutputStream fileStream = new FileOutputStream(destination);
            final long startTime = System.currentTimeMillis();
            cancelBtn.setVisible(true);
            progressBarLabel.setText("Downloading " + downloadingFile);
            // Make the UI update whilst the download is in progress: progress bar and balance label.
            ProgressOutputStream stream = new ProgressOutputStream(fileStream, downloadingFile.getSize());
            progressBar.progressProperty().bind(stream.progressProperty());
            Main.client.setOnPaymentMade((amt) -> Platform.runLater(this::refreshBalanceLabel));
            // Swap in the progress bar with an animation.
            animateSwap();
            // ... and start the download.
            downloadFuture = Main.client.downloadFile(downloadingFile, stream);
            final File fDestination = destination;
            // When we're done ...
            downloadFuture.handleAsync((ok, exception) -> {
                animateSwap();  // ... swap widgets back out again
                if (exception != null) {
                    if (!(exception instanceof CancellationException))
                        crashAlert(exception);
                } else {
                    // Otherwise inform the user we're finished and let them open the file.
                    int secondsTaken = (int) (System.currentTimeMillis() - startTime) / 1000;
                    runAlert((stage, controller) ->
                            controller.withOpenFile(stage, downloadingFile, fDestination, secondsTaken));
                }
                return null;
            }, Platform::runLater);
        } catch (ValueOutOfRangeException e) {
            if (destination != null)
                destination.delete();
            final String price = Utils.bitcoinValueToFriendlyString(BigInteger.valueOf(selectedFile.get().getPrice()));
            informationalAlert("Insufficient funds",
                    "This file costs %s BTC but you can't afford that. Try sending some money to this app first.", price);
        }
    }

    public void fileEntryClicked(MouseEvent event) throws Exception {
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            // Double click on a file: shortcut for downloading.
            download(null);
        }
    }

    public void cancelOperation(ActionEvent event) {
        downloadFuture.cancel(true);
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

    /** Swap the download/disconnect buttons for a progress bar + cancel button */
    public void animateSwap() {
        Node n1 = controlsBoxOnScreen ? controlsBox : syncBox;
        Node n2 = controlsBoxOnScreen ? syncBox : controlsBox;
        TranslateTransition leave = new TranslateTransition(Duration.millis(600), n1);
        leave.setByY(80.0);
        TranslateTransition arrive = new TranslateTransition(Duration.millis(600), n2);
        arrive.setToY(0.0);
        SequentialTransition both = new SequentialTransition(leave, arrive);
        both.setCycleCount(1);
        both.setInterpolator(Interpolator.EASE_BOTH);
        both.play();
        controlsBoxOnScreen = !controlsBoxOnScreen;
    }

    private class BlockChainSyncListener extends DownloadListener {
        @Override
        protected void progress(double pct, int blocksSoFar, Date date) {
            super.progress(pct, blocksSoFar, date);
            Platform.runLater(() -> progressBar.setProgress(pct / 100.0));
        }

        @Override
        protected void doneDownload() {
            super.doneDownload();
            Platform.runLater(Controller.this::readyToGoAnimation);
        }
    }

    public BlockChainSyncListener progressBarUpdater() {
        return new BlockChainSyncListener();
    }

    private class BalanceUpdater extends AbstractWalletEventListener {
        @Override
        public void onWalletChanged(Wallet wallet) {
            refreshBalanceLabel();
        }
    }

    public void refreshBalanceLabel() {
        checkGuiThread();
        BigInteger amount = getBalance();
        balance.setText(Utils.bitcoinValueToFriendlyString(amount));
    }

    private BigInteger getBalance() {
        BigInteger amount;
        if (Main.client != null)
            amount = Main.client.getRemainingBalance();
        else
            amount = Main.bitcoin.wallet().getBalance();
        return amount;
    }

    public void prepareForDisplay(List<PayFileClient.File> files) {
        this.files.setAll(files);
        refreshBalanceLabel();
    }
}
