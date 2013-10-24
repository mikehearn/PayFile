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

import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import net.plan99.payfile.ProtocolException;
import net.plan99.payfile.client.PayFileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.plan99.payfile.gui.utils.GuiUtils.*;

/** A class that manages the connect to server screen. */
public class ConnectServerController {
    private final static Logger log = LoggerFactory.getLogger(ConnectServerController.class);
    private final static int REFUND_CONNECT_TIMEOUT_MSEC = 1000;

    public Button connectBtn;
    public TextField server;
    public Label titleLabel;
    public Main.OverlayUI overlayUi;
    private String defaultTitle;

    // Called by FXMLLoader
    public void initialize() {
        server.textProperty().addListener((observableValue, prev, current) -> connectBtn.setDisable(current.trim().isEmpty()));
        defaultTitle = titleLabel.getText();
        // Restore the server used last time, minus the port part if it was the default.
        HostAndPort lastServer = Settings.getLastServer();
        if (lastServer != null)
            server.setText(lastServer.getPort() == PayFileClient.PORT ? lastServer.getHostText() : lastServer.toString());
    }

    public void connect(ActionEvent event) {
        final String serverName = server.getText().trim();
        HostAndPort hostPort = verifyServerName(serverName);
        if (hostPort == null)
            return;
        connectBtn.setDisable(true);

        maybeSettleLastServer(hostPort).thenRun(() -> {
            titleLabel.setText(String.format("Connecting to %s...", serverName));

            Main.connect(hostPort).handle((client, ex) -> {
                if (ex != null) {
                    Platform.runLater(() -> handleConnectError(Throwables.getRootCause(ex), serverName));
                    return null;
                }
                Main.client = client;
                return client.queryFiles().whenCompleteAsync((files, ex2) -> {
                    if (ex2 != null) {
                        handleQueryFilesError(ex2);
                    } else {
                        Settings.setLastServer(hostPort);
                        showUIWithFiles(files);
                    }
                }, Platform::runLater);
            });
        });
    }

    /**
     * Possibly reconnect to the last paid server and ask it to give us back the money. Note that after 24 hours this
     * channel will expire anyway, so if the server is gone, it's not the end of the world, we'll still get the money
     * back. The returned future completes immediately if nothing needs to be done.
     */
    private CompletableFuture<Void> maybeSettleLastServer(HostAndPort newServerName) {
        final HostAndPort lastPaidServer = Settings.getLastPaidServer();
        // If we didn't have a payment channel, or we did but it's with the same server we're connecting to, ignore.
        if (lastPaidServer == null || newServerName.equals(lastPaidServer))
            return CompletableFuture.completedFuture(null);
        BigInteger amountInLastServer = PayFileClient.getBalanceForServer(
                lastPaidServer.getHostText(), lastPaidServer.getPort(), Main.bitcoin.wallet());
        // If the last server we paid was already settled, ignore.
        if (amountInLastServer.compareTo(BigInteger.ZERO) == 0)
            return CompletableFuture.completedFuture(null);
        // Otherwise we have some money locked up with the last server. Ask for it back.
        final CompletableFuture<Void> future = new CompletableFuture<>();
        titleLabel.setText(String.format("Contacting %s to request early settlement ...", lastPaidServer));
        log.info("Connecting to {}", lastPaidServer);
        Main.connect(lastPaidServer, REFUND_CONNECT_TIMEOUT_MSEC).whenCompleteAsync((client, ex) -> {
            if (ex == null) {
                log.info("Connected. Requesting early settlement.");
                titleLabel.setText("Requesting early settlement ...");
                client.settlePaymentChannel().whenCompleteAsync((v, settleEx) -> {
                    if (settleEx == null) {
                        log.info("Settled. Proceeding ...");
                        client.disconnect();
                        future.complete(null);
                    } else {
                        crashAlert(settleEx);
                    }
                }, Platform::runLater);
            } else {
                log.error("Failed to connect", ex);
                titleLabel.setText(defaultTitle);
                informUserTheyMustWait(lastPaidServer);
            }
        }, Platform::runLater);
        return future;
    }

    private HostAndPort verifyServerName(String serverName) {
        try {
            return HostAndPort.fromString(serverName).withDefaultPort(PayFileClient.PORT);
        } catch (IllegalArgumentException e) {
            informationalAlert("Invalid server name",
                    "Could not understand server name '%s'. Try something like 'riker.plan99.net'.", serverName);
            return null;
        }
    }

    private void showUIWithFiles(List<PayFileClient.File> files) {
        checkGuiThread();
        Main.instance.controller.prepareForDisplay(files);
        fadeIn(Main.instance.mainUI);
        Main.instance.mainUI.setVisible(true);
        overlayUi.done();
    }

    private void handleConnectError(Throwable ex, String serverName) {
        checkGuiThread();
        titleLabel.setText(defaultTitle);
        connectBtn.setDisable(false);
        String message = ex.toString();
        // More friendly message for the most common failure kinds.
        if (ex instanceof UnknownHostException) {
            message = "No server with that name found.";
        } else if (ex instanceof SocketTimeoutException) {
            message = "Connection timed out: server did not respond quickly enough";
        } else if (ex instanceof ConnectException) {
            message = "Connection refused: there's no PayFile server running at that address";
        }
        informationalAlert("Failed to connect to '" + serverName + "'", message);
    }

    private void handleQueryFilesError(Throwable ex2) {
        if (ex2 instanceof ProtocolException && ((ProtocolException) ex2).getCode() == ProtocolException.Code.NETWORK_MISMATCH) {
            informationalAlert("Network mismatch", "The remote server is not using the same crypto-currency as you.%n%s",
                    ex2.getMessage());
            return;
        }
        crashAlert(ex2);
    }

    private void informUserTheyMustWait(HostAndPort lastPaidServer) {
        int seconds = (int) PayFileClient.getSecondsUntilExpiry(lastPaidServer.getHostText(), lastPaidServer.getPort(),
                Main.bitcoin.wallet());

        StringBuilder time = new StringBuilder();
        int minutes = seconds / 60;
        int hours = minutes / 60;
        if (hours > 0) {
            if (hours == 1)
                time.append("1 hour and ");
            else
                time.append(hours).append(" hours and ");
            minutes %= 60;
        }
        time.append(minutes).append(" minutes");

        informationalAlert("Connection failed", "Could not contact '%s' to request an early settlement of your "
                + "previous payments. You must wait approximately %s until automatic "
                + "settlement is possible. At that time all refunds will be returned.", lastPaidServer, time);
    }
}
