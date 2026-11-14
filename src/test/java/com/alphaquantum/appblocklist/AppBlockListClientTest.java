package com.alphaquantum.appblocklist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alphaquantum.appblocklist.AppBlockListException.InvalidIdException;
import com.alphaquantum.appblocklist.AppBlockListException.QuotaExceededException;
import com.alphaquantum.appblocklist.AppBlockListException.ServerException;
import com.alphaquantum.appblocklist.AppBlockListException.UnauthorizedException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Offline tests: documented API answers parsed without network access. */
class AppBlockListClientTest {

    static final String TIKTOK = "{\n"
            + "  \"found\": true,\n"
            + "  \"name\": \"TikTok - Videos, Shop & LIVE\",\n"
            + "  \"developer\": \"TikTok Ltd.\",\n"
            + "  \"bundle_id\": \"com.zhiliaoapp.musically\",\n"
            + "  \"app_store_id\": \"835599320\",\n"
            + "  \"android_package\": \"com.zhiliaoapp.musically\",\n"
            + "  \"app_store_genre\": \"Entertainment\",\n"
            + "  \"age_rating\": \"12+\",\n"
            + "  \"filtering_categories\": [\"Social Networking\", \"Streaming Media\"],\n"
            + "  \"developer_domain\": \"tiktok.com\",\n"
            + "  \"iab_category\": \"Technology & Computing > Computing > Internet > Social Networking\"\n"
            + "}";

    @Test
    void parsesFoundApp() {
        AppInfo app = AppBlockListClient.parseResponse(200, TIKTOK);
        assertTrue(app.isFound());
        assertEquals("TikTok - Videos, Shop & LIVE", app.getName());
        assertEquals("TikTok Ltd.", app.getDeveloper());
        assertEquals("835599320", app.getAppStoreId());
        assertEquals("Entertainment", app.getAppStoreGenre());
        assertEquals("12+", app.getAgeRating());
        assertEquals(12, app.getMinimumAge());
        assertEquals(Arrays.asList("Social Networking", "Streaming Media"), app.getFilteringCategories());
        assertEquals("Social Networking", app.getPrimaryCategory().orElse(""));
        assertTrue(app.inCategory("streaming media"));
        assertFalse(app.inCategory("Shopping"));
        assertTrue(app.isOnGooglePlay());
        assertEquals(List.of("com.zhiliaoapp.musically"), app.getBlockIds());
        assertEquals("tiktok.com", app.getDeveloperDomain());
        assertEquals("", app.getMessage());
    }

    @Test
    void notFoundIsAResultNotAnException() {
        AppInfo missing = AppBlockListClient.parseResponse(404, "{\"found\": false, \"error\": \"App not found.\"}");
        assertFalse(missing.isFound());
        assertEquals("App not found.", missing.getMessage());
        assertTrue(missing.getFilteringCategories().isEmpty());

        AppInfo playOnly = AppBlockListClient.parseResponse(404,
                "{\"found\": false, \"android_package\": \"com.example.app\", "
                        + "\"error\": \"Listed on Google Play, not yet in the categorized database.\"}");
        assertFalse(playOnly.isFound());
        assertEquals("com.example.app", playOnly.getAndroidPackage());
        assertTrue(playOnly.isOnGooglePlay());
    }

    @Test
    void mapsErrorStatuses() {
        assertThrows(InvalidIdException.class,
                () -> AppBlockListClient.parseResponse(400, "{\"error\":\"bad id\"}"));
        assertThrows(UnauthorizedException.class,
                () -> AppBlockListClient.parseResponse(401, "{\"error\":\"Invalid api_key.\"}"));
        QuotaExceededException q = assertThrows(QuotaExceededException.class,
                () -> AppBlockListClient.parseResponse(403, "{\"error\":\"Monthly lookups used.\",\"monthly_lookups\":100000}"));
        assertEquals(100000L, q.getMonthlyLookups());
        assertEquals(403, q.getStatusCode());
        ServerException s = assertThrows(ServerException.class,
                () -> AppBlockListClient.parseResponse(500, "{\"error\":\"Temporary error.\"}"));
        assertTrue(s.isTemporary());
        assertEquals("Temporary error.", s.getMessage());
    }

    @Test
    void lenientFields() {
        AppInfo app = AppBlockListClient.parseResponse(200,
                "{\"found\":true,\"app_store_id\":835599320,\"filtering_categories\":null,\"name\":\"\\u00c9cole\"}");
        assertEquals("835599320", app.getAppStoreId());
        assertTrue(app.getFilteringCategories().isEmpty());
        assertEquals("\u00c9cole", app.getName());
        assertThrows(AppBlockListException.class, () -> AppBlockListClient.parseResponse(200, "not json"));
    }

    @Test
    void validatesIdsLocally() {
        assertEquals("id835599320", AppBlockListClient.validateId("  id835599320 "));
        assertThrows(InvalidIdException.class, () -> AppBlockListClient.validateId(""));
        assertThrows(InvalidIdException.class, () -> AppBlockListClient.validateId(null));
        assertThrows(InvalidIdException.class, () -> AppBlockListClient.validateId("com.example app"));
        assertThrows(InvalidIdException.class, () -> AppBlockListClient.validateId("a".repeat(201)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonReaderHandlesNesting() {
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\":[1,2.5,{\"b\":null}],\"c\":\"x\\\"y\",\"d\":false}");
        assertEquals("x\"y", m.get("c"));
        assertEquals(Boolean.FALSE, m.get("d"));
        assertEquals(3, ((List<Object>) m.get("a")).size());
    }
}
