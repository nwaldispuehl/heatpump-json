package ch.retorte.heatpump;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;

@Singleton
public class HeatpumpDataFetcher {

    // ---- Statics

    private static final Logger LOG = Logger.getLogger(HeatpumpDataFetcher.class);

    private static final String SUB_PROTOCOL = "Lux_WS";
    private static final String URL_PATTERN = "ws://%s:8214";
    private static final String LOGIN_COMMAND = "LOGIN;0";
    private static final String SELECT_DATA_PATTERN = "GET;%s";
    private static final String REFRESH_COMMAND = "REFRESH";


    // ---- Injects

    @Inject
    HeatpumpDataConverter dataConverter;

    @Inject
    ManagedExecutor executor;

    // ---- Fields

    @ConfigProperty(name = "heatpump.address")
    String heatpumpAddress;

    private boolean active = false;
    private long lastRefresh = -1;

    private final StateMachine stateMachine = new StateMachine();


    // ---- Methods

    public List<Item> getCurrentTopLevelItems() {
        return stateMachine.getItems();
    }

    public long getLastRefresh() {
        return lastRefresh;
    }

    public boolean hasData() {
        return lastRefresh != -1;
    }

    void onStart(@Observes StartupEvent event) {
        stateMachine.operate();
        active = true;
    }

    void onStop(@Observes ShutdownEvent event) {
        active = false;
        stateMachine.terminate();
    }

    @Scheduled(cron = "${heatpump.fetch.cron}")
    public void invoke() {
        if (active) {
            stateMachine.operate();
        }
    }


    // ---- Inner classes

    private class StateMachine {

        private static final int ERROR_COOLDOWN_ITERATIONS = 100;

        private State state = State.NEW;
        private WebSocket webSocket;
        private String address;
        private int errorCount = 0;
        private int errorCooldown = ERROR_COOLDOWN_ITERATIONS;
        private final List<Item> items = new ArrayList<>();

        public StateMachine() {
            LOG.info("Initializing with state: " + state);
        }

        public void setOpen() {
            LOG.info("Opened WebSocket connection to: " + getHeatpumpUrl());
            updateState(State.OPEN);
            operate();
        }

        public void setLoggedInWith(String address) {
            this.address = address;
            updateState(State.LOGGED_IN);
            operate();
        }

        public void setDataSelected() {
            updateState(State.DATA_SELECTED);
        }

        public void setClose() {
            updateState(State.NEW);
            operate();
        }

        public void setError() {
            if (errorCount < 3) {
                errorCount++;
                setClose();
            }
            else {
                updateState(State.ERROR);
                LOG.error("Software now in ERROR state after 3 attempts.");
            }
        }

        private void updateState(State s) {
            LOG.info("Update state: " + state + " -> " + s);
            state = s;
        }

        public void refresh(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        private void operate() {
            switch (state) {
                case NEW -> executor.runAsync(() ->createWebSocketWith(new WebSocketListener(this)));
                case OPEN -> websocketSend(LOGIN_COMMAND);
                case LOGGED_IN -> websocketSend(format(SELECT_DATA_PATTERN, address));
                case DATA_SELECTED -> websocketSend(REFRESH_COMMAND);
                case ERROR -> handleErrorOperation();
            }
        }

        private void websocketSend(String message) {
            LOG.debug("Sending message " + message);
            try {
                webSocket.sendText(message, true).get();
            }
            catch (InterruptedException | ExecutionException e) {
                if (active) {
                    LOG.error("Unable to send websocket message: " + e.getMessage());
                }
            }
        }

        private void handleErrorOperation() {
            if (0 < errorCooldown) {
                LOG.warn("Waiting for error to cool down (" + errorCooldown + " more iterations)");
                errorCooldown--;
            }
            else {
                resetError();
            }
        }

        private void resetError() {
            errorCount = 0;
            errorCooldown = ERROR_COOLDOWN_ITERATIONS;
            LOG.info("Resetting error state to new.");
            setClose();
        }

        private void createWebSocketWith(WebSocket.Listener listener) {
            try(final HttpClient client = HttpClient.newBuilder().build()) {
                final WebSocket.Builder builder = client.newWebSocketBuilder();
                CompletableFuture<WebSocket> webSocket = builder
                    .connectTimeout(Duration.ofSeconds(30))
                    .subprotocols(SUB_PROTOCOL)
                    .buildAsync(new URI(getHeatpumpUrl()), listener);
                webSocket.get();
            }
            catch (URISyntaxException | InterruptedException | ExecutionException e) {
                LOG.error("Error creating websocket: " + e.getMessage());
            }
        }

        private String getHeatpumpUrl() {
            return format(URL_PATTERN, heatpumpAddress);
        }

        public synchronized void setItems(List<Item> items) {
            this.items.clear();
            this.items.addAll(items);
            updateLastRefresh();
        }

        public synchronized void refreshItemsWith(Map<String, String> updateIdValueMap) {
            refreshFor(items, updateIdValueMap);
            updateLastRefresh();
        }

        private void refreshFor(List<Item> list, Map<String, String> map) {
            for (Item item : list) {

                if (map.containsKey(item.getNodeId())) {
                    item.setRawValue(map.get(item.getNodeId()));
                }

                if (item.hasChildren()) {
                    refreshFor(item.getChildren(), map);
                }
            }
        }

        private void updateLastRefresh() {
            lastRefresh = System.currentTimeMillis() / 1000;
        }

        public synchronized List<Item> getItems() {
            return items;
        }

        public void terminate() {
            LOG.info("Terminating WebSocket connection to: " + getHeatpumpUrl());
            if (webSocket != null) {
                webSocket.abort();
            }
        }

        private enum State {
            NEW,
            OPEN,
            LOGGED_IN,
            DATA_SELECTED,
            ERROR
        }
    }

    private class WebSocketListener implements WebSocket.Listener {

        private final StateMachine stateMachine;
        private String buffer = "";

        WebSocketListener(StateMachine stateMachine) {
            this.stateMachine = stateMachine;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            stateMachine.refresh(webSocket);
            LOG.debug("WebSocket opened " + webSocket.toString());

            WebSocket.Listener.super.onOpen(webSocket);

            stateMachine.setOpen();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            stateMachine.refresh(webSocket);

            buffer += data.toString();
            if (!last) {
                return WebSocket.Listener.super.onText(webSocket, data, false);
            }

            String content = buffer;
            buffer = "";

            LOG.debug("WebSocket data (Size: " + content.length() + "): \n" + content + "\n");

            if (content.startsWith("<Navigation")) {
                String address = extractAddressFrom(content);
                stateMachine.setLoggedInWith(address);
            }
            else if (content.startsWith("<Content")) {
                List<Item> items = extractItemsFromContent(content);
                stateMachine.setItems(items);
                stateMachine.setDataSelected();
            }
            else if (content.startsWith("<values")) {
                Map<String, String> updateIdValueMap = getUpdateIdValueMapOf(content);
                stateMachine.refreshItemsWith(updateIdValueMap);
            }

            return WebSocket.Listener.super.onText(webSocket, data, true);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            stateMachine.refresh(webSocket);
            LOG.error("WebSocket error: " + error.getMessage());
            stateMachine.setError();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            stateMachine.refresh(webSocket);
            LOG.warn("WebSocket closed: " + reason);
            stateMachine.setClose();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        private String extractAddressFrom(String navigationXml) {
                Document document = parse(navigationXml);
                NodeList navigationNodes = document.getElementsByTagName("Navigation");
                if (1 <= navigationNodes.getLength()) {
                    NodeList items = navigationNodes.item(0).getChildNodes();
                    if (1 <= items.getLength()) {
                        Node id = items.item(0).getAttributes().getNamedItem("id");
                        return id.getNodeValue();
                    }
                }
                return null;
        }

        private List<Item> extractItemsFromContent(String contentXml) {
            Document document = parse(contentXml);
            Node contentNode = document.getFirstChild();
            NodeList topicNodes = contentNode.getChildNodes();

            return convertToItems(topicNodes);
        }

        private List<Item> convertToItems(NodeList nodes) {
            List<Item> result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node currentNode = nodes.item(i);
                if (currentNode.getNodeName().equals("item") && 1 <= currentNode.getChildNodes().getLength()) {
                    String currentNodeId = currentNode.getAttributes().getNamedItem("id").getNodeValue();
                    String currentNodeName = currentNode.getFirstChild().getFirstChild().getNodeValue();
                    Node valueChild = getValueChildNode(currentNode);
                    String rawValue = valueChild != null ? valueChild.getTextContent() : "";

                    HeatpumpDataConverter.UnitInfo unitInfo = dataConverter.getFor(currentNodeName, rawValue);

                    if (unitInfo == null) {
                        continue;
                    }

                    Item item = new Item(dataConverter.bundle(), currentNodeName, currentNodeId, unitInfo);

                    if (valueChild != null) {
                        // Enumerate and convert to objects.
                        item.setRawValue(rawValue);
                    }
                    else {
                        // If the 'currentNode' has no value it must be a title node, then recurse over the children, and add them as children each.
                        item.addChildren(convertToItems(currentNode.getChildNodes()));
                    }

                    result.add(item);
                }
            }

            return result;
        }

        private Node getValueChildNode(Node node) {
            final NodeList childNodes = node.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node child = childNodes.item(i);
                if (child.getNodeName().equals("value")) {
                    return child;
                }
            }
            return null;
        }

        private Map<String, String> getUpdateIdValueMapOf(String refreshXml) {
            Map<String, String> result = new HashMap<>();

            Document document = parse(refreshXml);
            Node valuesNode = document.getFirstChild();
            NodeList topicNodes = valuesNode.getChildNodes();

            processUpdateSubtree(topicNodes, result);

            return result;
        }

        private void processUpdateSubtree(NodeList nodes, Map<String, String> map) {
            for (int i = 0; i < nodes.getLength(); i++) {
                Node currentNode = nodes.item(i);
                if (currentNode.getNodeName().equals("item") && 1 <= currentNode.getChildNodes().getLength()) {
                    String currentNodeId = currentNode.getAttributes().getNamedItem("id").getNodeValue();

                    Node valueChild = getValueChildNode(currentNode);
                    if (valueChild != null) {
                        // Enumerate and convert to objects.
                        map.put(currentNodeId, valueChild.getTextContent());
                    }
                    else {
                        // If the 'currentNode' has no value, recurse, and use it as parent.
                        processUpdateSubtree(currentNode.getChildNodes(), map);
                    }
                }
            }
        }

        private Document parse(String xml) {
            try {
                DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                InputSource is = new InputSource();
                is.setCharacterStream(new StringReader(xml));
                return db.parse(is);
            }
            catch (ParserConfigurationException | SAXException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
