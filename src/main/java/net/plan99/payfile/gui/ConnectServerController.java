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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import net.plan99.payfile.ProtocolException;
import net.plan99.payfile.client.PayFileClient;
import net.plan99.payfile.gui.utils.GuiUtils;

import java.net.UnknownHostException;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class ConnectServerController {
    public Button connectBtn;
    public TextField server;
    public Label titleLabel;
    public Main.OverlayUI overlayUi;

    // Called by FXMLLoader
    public void initialize() {
        server.textProperty().addListener((observableValue, prev, current) -> {
            connectBtn.setDisable(current.trim().isEmpty());
        });

        // Temp for testing
        server.setText("localhost");
    }

    public void connect(ActionEvent event) {
        final String serverName = server.getText();
        checkState(!serverName.trim().isEmpty());
        final String previousTitle = titleLabel.getText();
        titleLabel.setText("Connecting ...");
        Futures.addCallback(Main.instance.connect(serverName), new FutureCallback<PayFileClient>() {
            @Override
            public void onSuccess(PayFileClient result) {
                Main.client = checkNotNull(result);
                queryFiles(result);
            }

            @Override
            public void onFailure(Throwable t) {
                titleLabel.setText(previousTitle);
                String message = t.toString();
                if (t instanceof UnknownHostException) {
                    // More friendly message for the most common failure kind.
                    message = "Could not find domain name of server.";
                }
                GuiUtils.informationalAlert("Failed to connect to '" + serverName + "'", message);
            }
        }, Platform::runLater);
    }

    private void queryFiles(PayFileClient client) {
        // Ask the server what files it has.
        checkState(Platform.isFxApplicationThread());
        Futures.addCallback(client.queryFiles(), new FutureCallback<List<PayFileClient.File>>() {
            @Override
            public void onSuccess(List<PayFileClient.File> result) {
                Main.instance.controller.setFiles(result);
                GuiUtils.fadeIn(Main.instance.mainUI);
                Main.instance.mainUI.setVisible(true);
                overlayUi.done();
            }

            @Override
            public void onFailure(Throwable t) {
                if (t instanceof ProtocolException) {
                    final ProtocolException protocolException = (ProtocolException) t;
                    if (protocolException.getCode() == ProtocolException.Code.NETWORK_MISMATCH) {
                        String errorMsg = String.format("The remote server is not using the same crypto-currency as " +
                                "you.%n%s", protocolException.getMessage());
                        GuiUtils.informationalAlert("Network mismatch", errorMsg);
                        return;
                    }
                }
                GuiUtils.crashAlert(t);
            }
        }, Platform::runLater);
    }
}
