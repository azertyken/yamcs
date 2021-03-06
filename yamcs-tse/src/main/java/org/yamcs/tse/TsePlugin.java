package org.yamcs.tse;

import java.io.IOException;

import org.yamcs.Plugin;
import org.yamcs.PluginException;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;

public class TsePlugin implements Plugin {

    @Override
    public void onLoad() throws PluginException {
        YamcsServer yamcs = YamcsServer.getServer();

        // Activate TSE Commander (only if user did not manually add this service)
        if (yamcs.getGlobalServices(TseCommander.class).isEmpty()
                && YConfiguration.isDefined("tse")) {
            addTseCommander(yamcs);
        }
    }

    /**
     * Starts the TseCommander. These can be configured in tse.yaml
     */
    private void addTseCommander(YamcsServer yamcs) throws PluginException {
        YConfiguration yconf = YConfiguration.getConfiguration("tse");
        try {
            yamcs.addGlobalService("TSE Commander", TseCommander.class, yconf);
        } catch (ValidationException e) {
            throw new PluginException("Invalid configuration: " + e.getMessage());
        } catch (IOException e) {
            throw new PluginException("Could not start TSE Commander", e);
        }
    }
}
