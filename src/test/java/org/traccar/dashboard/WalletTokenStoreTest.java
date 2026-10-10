package org.traccar.dashboard;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Attribute;
import org.traccar.storage.MemoryStorage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WalletTokenStoreTest {

    private Attribute add(MemoryStorage storage, String name, String expression, String type) {
        var attribute = new Attribute();
        attribute.setDescription("Wallet API token");
        attribute.setAttribute(name);
        attribute.setExpression(expression);
        attribute.setType(type);
        attribute.setId(storage.addObject(attribute, new Request(new Columns.Exclude("id"))));
        return attribute;
    }

    @Test
    public void testExistingComputedAttributeSuppliesTokenAcrossInstances() throws Exception {
        var storage = new MemoryStorage();
        add(storage, "wallet_token", "'test-only-token'", "string");
        assertEquals("test-only-token", new WalletTokenStore(new Config(), storage).getToken());
        assertEquals("test-only-token", new WalletTokenStore(new Config(), storage).getToken());
    }

    @Test
    public void testUpdatingAndRemovingComputedAttributeChangesActiveToken() throws Exception {
        var storage = new MemoryStorage();
        var attribute = add(storage, "wallet_token", "\"first-token\"", "string");
        var store = new WalletTokenStore(new Config(), storage);
        assertEquals("first-token", store.getToken());
        attribute.setExpression("'replacement-token'");
        assertEquals("replacement-token", store.getToken());
        storage.removeObject(Attribute.class, new Request(new Condition.Equals("id", attribute.getId())));
        assertNull(store.getToken());
    }

    @Test
    public void testOptionalConfigurationFallback() throws Exception {
        var config = new Config();
        config.setString(Keys.WALLET_TOKEN, "configured-token");
        var storage = new MemoryStorage();
        var store = new WalletTokenStore(config, storage);
        assertEquals("configured-token", store.getToken());
        add(storage, "wallet_token", "'computed-token'", "string");
        assertEquals("computed-token", store.getToken());
    }

    @Test
    public void testOnlyOneTokenDefinitionCanBeUsed() {
        var storage = new MemoryStorage();
        add(storage, "wallet_token", "'one'", "string");
        add(storage, "wallet_token", "'two'", "string");
        var error = assertThrows(StorageException.class, () -> new WalletTokenStore(new Config(), storage).getToken());
        assertEquals("walletTokenDuplicate", error.getMessage());
    }

    @Test
    public void testTokenExpressionsMustBeConstantStrings() {
        for (String expression : new String[] {
                "plain-token", "'one' + 'two'", "''", "'two words'", "'token\\n'", "'unclosed", "42"}) {
            var storage = new MemoryStorage();
            add(storage, "wallet_token", expression, "string");
            var error = assertThrows(StorageException.class, () -> new WalletTokenStore(new Config(), storage).getToken());
            assertEquals("walletTokenAttributeInvalid", error.getMessage());
        }
        var storage = new MemoryStorage();
        add(storage, "wallet_token", "'token'", "number");
        assertThrows(StorageException.class, () -> new WalletTokenStore(new Config(), storage).getToken());
    }

    @Test
    public void testOtherComputedAttributesAreNotReadAsTokens() throws Exception {
        var storage = new MemoryStorage();
        add(storage, "fuel", "42", "number");
        assertNull(new WalletTokenStore(new Config(), storage).getToken());
    }
}
