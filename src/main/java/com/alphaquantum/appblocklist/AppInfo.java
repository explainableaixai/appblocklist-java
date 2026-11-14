package com.alphaquantum.appblocklist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One answer from the App Lookup API. Immutable and thread-safe.
 *
 * <p>When {@link #isFound()} is false the other fields are empty, except
 * {@link #getAndroidPackage()} for apps listed on Google Play but not yet
 * categorized, and {@link #getMessage()}, which carries the API's text.
 */
public final class AppInfo {
    private final boolean found;
    private final String name;
    private final String developer;
    private final String bundleId;
    private final String appStoreId;
    private final String androidPackage;
    private final String appStoreGenre;
    private final String ageRating;
    private final List<String> filteringCategories;
    private final String developerDomain;
    private final String iabCategory;
    private final String message;

    private AppInfo(Map<String, Object> m, boolean forceNotFound) {
        this.found = !forceNotFound && Json.bool(m, "found");
        this.name = Json.str(m, "name");
        this.developer = Json.str(m, "developer");
        this.bundleId = Json.str(m, "bundle_id");
        this.appStoreId = Json.str(m, "app_store_id");
        this.androidPackage = Json.str(m, "android_package");
        this.appStoreGenre = Json.str(m, "app_store_genre");
        this.ageRating = Json.str(m, "age_rating");
        this.filteringCategories = Collections.unmodifiableList(Json.strings(m, "filtering_categories"));
        this.developerDomain = Json.str(m, "developer_domain");
        this.iabCategory = Json.str(m, "iab_category");
        this.message = Json.str(m, "error");
    }

    static AppInfo fromMap(Map<String, Object> m, boolean forceNotFound) {
        return new AppInfo(m, forceNotFound);
    }

    /** True when the app is in the categorized database. */
    public boolean isFound() { return found; }

    /** Store listing name, e.g. "TikTok - Videos, Shop &amp; LIVE". */
    public String getName() { return name; }

    public String getDeveloper() { return developer; }

    /** iOS bundle ID, e.g. "com.zhiliaoapp.musically". */
    public String getBundleId() { return bundleId; }

    /** App Store ID as a string of digits, without the "id" prefix. */
    public String getAppStoreId() { return appStoreId; }

    /** Google Play package of the same app, or an empty string. */
    public String getAndroidPackage() { return androidPackage; }

    /** One of 26 App Store genres. */
    public String getAppStoreGenre() { return appStoreGenre; }

    /** App Store age rating: "4+", "9+", "12+" or "17+". */
    public String getAgeRating() { return ageRating; }

    /** Filtering categories, ranked with the most descriptive first. Never null. */
    public List<String> getFilteringCategories() { return filteringCategories; }

    public String getDeveloperDomain() { return developerDomain; }

    /** IAB content category path. */
    public String getIabCategory() { return iabCategory; }

    /** The API's "error" text on a not-found answer, otherwise empty. */
    public String getMessage() { return message; }

    /** The first ranked filtering category, if any. */
    public Optional<String> getPrimaryCategory() {
        return filteringCategories.isEmpty() ? Optional.empty() : Optional.of(filteringCategories.get(0));
    }

    /** True when the app carries any of the given categories (case-insensitive). */
    public boolean inCategory(String... categories) {
        return inCategory(java.util.Arrays.asList(categories));
    }

    /** True when the app carries any of the given categories (case-insensitive). */
    public boolean inCategory(java.util.Collection<String> categories) {
        for (String have : filteringCategories) {
            String h = have.trim().toLowerCase(Locale.ROOT);
            for (String want : categories) {
                if (want != null && h.equals(want.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when an Android package name is known. */
    public boolean isOnGooglePlay() { return !androidPackage.isEmpty(); }

    /** Numeric part of the age rating ("12+" gives 12), or -1 when unknown. */
    public int getMinimumAge() {
        String a = ageRating.trim();
        if (a.endsWith("+")) {
            a = a.substring(0, a.length() - 1);
        }
        try {
            return Integer.parseInt(a);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Identifiers an MDM needs to block this app on both platforms. */
    public List<String> getBlockIds() {
        List<String> ids = new ArrayList<>();
        if (!bundleId.isEmpty()) {
            ids.add(bundleId);
        }
        if (!androidPackage.isEmpty() && !androidPackage.equals(bundleId)) {
            ids.add(androidPackage);
        }
        return ids;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppInfo)) return false;
        AppInfo a = (AppInfo) o;
        return found == a.found && name.equals(a.name) && developer.equals(a.developer)
                && bundleId.equals(a.bundleId) && appStoreId.equals(a.appStoreId)
                && androidPackage.equals(a.androidPackage) && appStoreGenre.equals(a.appStoreGenre)
                && ageRating.equals(a.ageRating) && filteringCategories.equals(a.filteringCategories)
                && developerDomain.equals(a.developerDomain) && iabCategory.equals(a.iabCategory)
                && message.equals(a.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(found, bundleId, appStoreId, androidPackage, name);
    }

    @Override
    public String toString() {
        return found
                ? "AppInfo{" + name + ", " + bundleId + ", " + ageRating + ", " + filteringCategories + "}"
                : "AppInfo{not found, " + message + "}";
    }
}
