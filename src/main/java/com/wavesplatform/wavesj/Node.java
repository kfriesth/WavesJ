package com.wavesplatform.wavesj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;

public class Node {
    public static final String DEFAULT_NODE = "https://testnode1.wavesnodes.com";

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<OrderBook> ORDER_BOOK = new TypeReference<OrderBook>() {};
    private static final TypeReference<List<Order>> ORDER_LIST = new TypeReference<List<Order>>() {};

    private final URI uri;
    private final CloseableHttpClient client = HttpClients.createDefault();

    public Node() {
        try {
            this.uri = new URI(DEFAULT_NODE);
        } catch (URISyntaxException e) {
            // should not happen
            throw new RuntimeException(e);
        }
    }

    public Node(String uri) throws URISyntaxException {
        this.uri = new URI(uri);
    }

    public int getHeight() throws IOException {
        return send("/blocks/height", "height").asInt();
    }

    public long getBalance(String address) throws IOException {
        return send("/addresses/balance/" + address, "balance").asLong();
    }

    public long getBalance(String address, int confirmations) throws IOException {
        return send("/addresses/balance/" + address + "/" + confirmations, "balance").asLong();
    }

    public long getBalance(String address, String assetId) throws IOException {
        return Asset.WAVES.equals(assetId)
                ? getBalance(address)
                : send("/assets/balance/" + address + "/" + assetId, "balance").asLong();
    }

    /**
     * Sends a signed transaction and returns its ID.
     * @param tx signed transaction (as created by static methods in Transaction class)
     * @return Transaction ID
     * @throws IOException
     */
    public String send(Transaction tx) throws IOException {
        return parse(exec(request(tx)), "id").asText();
    }

    private JsonNode send(String path, String key) throws IOException {
        return parse(exec(request(path)), key);
    }

    public String transfer(PrivateKeyAccount from, String toAddress, long amount, long fee, String message) throws IOException {
        Transaction tx = Transaction.makeTransferTx(from, toAddress, amount, null, fee, null, message);
        return send(tx);
    }

    public String transferAsset(PrivateKeyAccount from, String toAddress,
            long amount, String assetId, long fee, String feeAssetId, String message) throws IOException
    {
        Transaction tx = Transaction.makeTransferTx(from, toAddress, amount, assetId, fee, feeAssetId, message);
        return send(tx);
    }

    public String lease(PrivateKeyAccount from, String toAddress, long amount, long fee) throws IOException {
        Transaction tx = Transaction.makeLeaseTx(from, toAddress, amount, fee);
        return send(tx);
    }

    public String cancelLease(PrivateKeyAccount account, String txId, long fee) throws IOException {
        Transaction tx = Transaction.makeLeaseCancelTx(account, txId, fee);
        return send(tx);
    }

    public String issueAsset(PrivateKeyAccount account,
            String name, String description, long quantity, int decimals, boolean reissuable, long fee) throws IOException
    {
        Transaction tx = Transaction.makeIssueTx(account, name, description, quantity, decimals, reissuable, fee);
        return send(tx);
    }

    public String reissueAsset(PrivateKeyAccount account, String assetId, long quantity, boolean reissuable, long fee) throws IOException {
        Transaction tx = Transaction.makeReissueTx(account, assetId, quantity, reissuable, fee);
        return send(tx);
    }

    public String burnAsset(PrivateKeyAccount account, String assetId, long amount, long fee) throws IOException {
        Transaction tx = Transaction.makeBurnTx(account, assetId, amount, fee);
        return send(tx);
    }

    public String alias(PrivateKeyAccount account, String alias, char scheme, long fee) throws IOException {
        Transaction tx = Transaction.makeAliasTx(account, alias, scheme, fee);
        return send(tx);
    }

    // Matcher transactions

    public String getMatcherKey() throws IOException {
        return parse(exec(request("/matcher"))).asText();
    }

    public Order createOrder(PrivateKeyAccount account, String matcherKey, AssetPair assetPair, Order.Type orderType,
                             long price, long amount, long expiration, long matcherFee) throws IOException {
        Transaction tx = Transaction.makeOrderTx(account, matcherKey, orderType, assetPair, price, amount, expiration, matcherFee);
        JsonNode tree = parse(exec(request(tx)));
        // fix order status
        ObjectNode message = (ObjectNode) tree.get("message");
        message.put("status", tree.get("status").asText());
        return mapper.treeToValue(tree.get("message"), Order.class);
    }

    public String cancelOrder(PrivateKeyAccount account, AssetPair assetPair, String orderId) throws IOException {
        Transaction tx = Transaction.makeOrderCancelTx(account, assetPair, orderId);
        return parse(exec(request(tx)), "status").asText();
    }

    public OrderBook getOrderBook(AssetPair assetPair) throws IOException {
        String path = "/matcher/orderbook/" + assetPair.amountAsset+ '/' + assetPair.priceAsset;
        return parse(exec(request(path)), ORDER_BOOK);
    }

    public String getOrderStatus(String orderId, AssetPair assetPair) throws IOException {
        String path = "/matcher/orderbook/" + assetPair.amountAsset+ '/' + assetPair.priceAsset+ '/' + orderId;
        return send(path, "status").asText();
    }

    public List<Order> getOrders(PrivateKeyAccount account) throws IOException {
        return getOrders(account, "/matcher/orderbook/" + Base58.encode(account.getPublicKey()));
    }

    public List<Order> getOrders(PrivateKeyAccount account, AssetPair market) throws IOException {
        return getOrders(account, String.format("/matcher/orderbook/%s/%s/publicKey/%s",
                market.amountAsset, market.priceAsset, Base58.encode(account.getPublicKey())));
    }

    private List<Order> getOrders(PrivateKeyAccount account, String path) throws IOException {
        long timestamp = System.currentTimeMillis();
        ByteBuffer buf = ByteBuffer.allocate(40);
        buf.put(account.getPublicKey()).putLong(timestamp);
        String signature = Transaction.sign(account, buf);
        HttpResponse r = exec(request(path, "Timestamp", String.valueOf(timestamp), "Signature", signature));
        return parse(r, ORDER_LIST);
    }

    private <T> HttpUriRequest request(String path, String... headers) {
        HttpUriRequest req = new HttpGet(uri.resolve(path));
        for (int i = 0; i < headers.length; i +=2) {
            req.addHeader(headers[i], headers[i + 1]);
        }
        return req;
    }

    private HttpUriRequest request(Transaction tx) throws IOException {
        HttpPost request = new HttpPost(uri + tx.getEndpoint());
        request.setEntity(new StringEntity(tx.getJson()));
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        return request;
    }

    private HttpResponse exec(HttpUriRequest request) throws IOException {
        HttpResponse r = client.execute(request);
        if (r.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            String error = parse(r, "message").asText();
            throw new IOException(error);
        }
        return r;
    }

    private static <T> T parse(HttpResponse r, TypeReference<T> ref) throws IOException {
        return mapper.readValue(r.getEntity().getContent(), ref);
    }

    private static JsonNode parse(HttpResponse r, String... keys) throws IOException {
        JsonNode tree = mapper.readTree(r.getEntity().getContent());
        for (String key: keys) {
            tree = tree.get(key);
        }
        return tree;
    }
}
