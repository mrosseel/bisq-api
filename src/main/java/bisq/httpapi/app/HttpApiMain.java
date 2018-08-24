/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.httpapi.app;

import bisq.core.app.BisqExecutable;
import bisq.core.app.BisqHeadlessAppMain;

import bisq.common.UserThread;
import bisq.common.app.AppModule;
import bisq.common.setup.CommonSetup;

import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;



import bisq.httpapi.service.HttpApiServer;

/**
 * Main class for headless version.
 */
@Slf4j
public class HttpApiMain extends BisqHeadlessAppMain {

    public static void main(String[] args) throws Exception {
        if (BisqExecutable.setupInitialOptionParser(args)) {
            // For some reason the JavaFX launch process results in us losing the thread context class loader: reset it.
            // In order to work around a bug in JavaFX 8u25 and below, you must include the following code as the first line of your realMain method:
            Thread.currentThread().setContextClassLoader(HttpApiMain.class.getClassLoader());

            new HttpApiMain().execute(args);
        }
    }

    @Override
    protected void launchApplication() {
        headlessApp = new HttpApiHeadlessApp();
        CommonSetup.setup(HttpApiMain.this.headlessApp);

        UserThread.execute(this::onApplicationLaunched);
    }

    @Override
    protected AppModule getModule() {
        return new HttpApiHeadlessModule(bisqEnvironment);
    }

    @Override
    public void onSetupComplete() {
        log.info("onSetupComplete");

        HttpApiServer httpApiServer = injector.getInstance(HttpApiServer.class);
        httpApiServer.setHostShutdownHandler(() -> UserThread.runAfter(() -> this.gracefulShutDown(() -> log.debug("App shutdown complete")),
                200, TimeUnit.MILLISECONDS));
        httpApiServer.startServer();
    }
}
