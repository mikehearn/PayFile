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

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import net.plan99.payfile.gui.utils.ThrottledRunLater;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An output stream that forwards to another, but keeps track of how many bytes were written and exposes the progress
 * as a JavaFX bindable property which is guaranteed to update at a sane rate on the UI thread.
 */
public class ProgressOutputStream extends FilterOutputStream {
    private final DoubleProperty progress;
    private final ThrottledRunLater throttler;
    private final AtomicLong bytesSoFar;

    public ProgressOutputStream(OutputStream sink, long expectedSize) {
        this(sink, 0, expectedSize);
    }

    public ProgressOutputStream(OutputStream sink, long bytesSoFar, long expectedSize) {
        super(sink);
        this.bytesSoFar = new AtomicLong(bytesSoFar);
        this.progress = new SimpleDoubleProperty();
        this.throttler = new ThrottledRunLater(() -> progress.set(this.bytesSoFar.get() / (double) expectedSize));
    }

    @Override
    public void write(@Nonnull byte[] b) throws IOException {
        super.write(b);
        bytesSoFar.addAndGet(b.length);
        throttler.runLater();
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return progress;
    }
}
