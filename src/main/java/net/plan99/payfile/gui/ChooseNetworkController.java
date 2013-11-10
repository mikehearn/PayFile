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

import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.params.TestNet3Params;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;

/** A class that manages the selection of the network to connect to. */
public class ChooseNetworkController {

    public Button mainNetworkBtn;
    public Button testNet3Btn;
    public Button regTestBtn;
    public Main.OverlayUI overlayUi;

    // Called by FXMLLoader
    public void initialize() {
    }

    public void chooseNetwork(ActionEvent event) {

        clearChooseNetworkModal();

        if (event.getSource().equals(testNet3Btn)) {
            Main.params = TestNet3Params.get();
            Main.filePrefix = "testNet3";
        } else if (event.getSource().equals(mainNetworkBtn)) {
            Main.params = MainNetParams.get();
            Main.filePrefix = "mainNet";
        } else if (event.getSource().equals(regTestBtn)) {
            Main.params = RegTestParams.get();
            Main.filePrefix = "regTest";
        }

        Main.chooseNetwork();
    }

    public void clearChooseNetworkModal() {
        mainNetworkBtn.setDisable(true);
        testNet3Btn.setDisable(true);
        regTestBtn.setDisable(true);
        overlayUi.done();
    }
}
