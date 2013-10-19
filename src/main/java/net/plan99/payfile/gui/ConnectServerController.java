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

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import net.plan99.payfile.ProtocolException;
import net.plan99.payfile.client.PayFileClient;

import java.net.UnknownHostException;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static net.plan99.payfile.gui.utils.GuiUtils.*;

public class ConnectServerController {
    public Button connectBtn;
    public TextField server;
    public Label titleLabel;
    public Main.OverlayUI overlayUi;

    // Called by FXMLLoader
    public void initialize() {
        server.textProperty().addListener((observableValue, prev, current) -> connectBtn.setDisable(current.trim().isEmpty()));

        // Temp for testing
        server.setText("localhost");
    }

    public void connect(ActionEvent event) {
        final String serverName = server.getText();
        checkState(!serverName.trim().isEmpty());
        final String previousTitle = titleLabel.getText();
        titleLabel.setText("Connecting ...");

        Main.instance.connect(serverName).handle((client, ex) -> {
            if (ex != null) {
                Platform.runLater(() -> handleConnectError(ex, serverName, previousTitle));
                return null;
            }
            Main.client = client;
            return client.queryFiles().handleAsync((files, ex2) -> {
                if (ex2 != null)
                    handleQueryFilesError(ex2);
                else
                    showUIWithFiles(files);
                return null;
            }, Platform::runLater);
        });
    }

    private void showUIWithFiles(List<PayFileClient.File> files) {
        checkGuiThread();
        Main.instance.controller.prepareForDisplay(files);
        fadeIn(Main.instance.mainUI);
        Main.instance.mainUI.setVisible(true);
        overlayUi.done();
    }

    private void handleConnectError(Throwable ex, String serverName, String previousTitle) {
        checkGuiThread();
        titleLabel.setText(previousTitle);
        String message = ex.toString();
        if (ex instanceof UnknownHostException) {
            // More friendly message for the most common failure kind.
            message = "Could not find domain name of server.";
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
}
