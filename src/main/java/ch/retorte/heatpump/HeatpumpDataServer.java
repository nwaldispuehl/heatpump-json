package ch.retorte.heatpump;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/")
public class HeatpumpDataServer {

    // ---- Injects

    @Inject
    HeatpumpDataFetcher fetcher;


    // ---- Fields

    @ConfigProperty(name = "version")
    String version;

    @ConfigProperty(name = "commit")
    String commit;

    @ConfigProperty(name = "heatpump.language")
    String heatpumpLanguage;

    @ConfigProperty(name = "heatpump.address")
    String heatpumpAddress;


    // ---- Methods

    void onStart(@Observes StartupEvent event) {
        checkProperties();
    }

    private void checkProperties() {
        if (heatpumpLanguage == null) {
            Log.error("Config property 'HEATPUMP_LANGUAGE' (e.g. 'de') is required");
        }
        if (heatpumpAddress == null) {
            Log.error("Config property 'HEATPUMP_ADDRESS' (e.g. '192.168.1.2') is required");
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Object heatpumpData() {
        final Map<String, Object> heatpumpData = new HashMap<>();

        while(!fetcher.hasData()) {
            waitOneSecond();
        }

        heatpumpData.put("data", convertToFlatItemList(fetcher.getCurrentTopLevelItems()));
        heatpumpData.put("metadata", metadata());

        return heatpumpData;
    }

    private void waitOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // nop
        }
    }

    private List<Item> convertToFlatItemList(List<Item> topLevelItems) {
        List<Item> result = new ArrayList<>();
        for (Item item : topLevelItems) {
            if (item.isLeaf()) {
                result.add(item);
            }
            else {
                result.addAll(convertToFlatItemList(item.getChildren()));
            }
        }
        return result;
    }

    private Map<String, Object> metadata() {
        final Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", version);
        metadata.put("commit", commit);
        metadata.put("timestamp", fetcher.getLastRefresh());
        return metadata;
    }


}
