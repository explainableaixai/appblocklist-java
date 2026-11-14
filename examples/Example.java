import com.alphaquantum.appblocklist.AppBlockListClient;
import com.alphaquantum.appblocklist.AppBlockListClient.LookupOutcome;
import com.alphaquantum.appblocklist.AppBlockListException;
import com.alphaquantum.appblocklist.AppBlockListException.QuotaExceededException;
import com.alphaquantum.appblocklist.AppInfo;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Looks up the apps given on the command line and prints a verdict for each.
 *
 * <pre>
 * mvn -q package
 * javac -cp target/appblocklist-1.0.0.jar -d target/examples examples/Example.java
 * APPBLOCKLIST_API_KEY=... java -cp target/appblocklist-1.0.0.jar:target/examples Example \
 *     com.zhiliaoapp.musically id835599320 com.openai.chatgpt
 * </pre>
 */
public class Example {
    private static final List<String> BLOCKED = List.of("Social Networking", "Streaming Media");

    public static void main(String[] args) {
        List<String> ids = args.length > 0 ? Arrays.asList(args) : List.of("com.zhiliaoapp.musically");
        AppBlockListClient client = AppBlockListClient.fromEnv();

        List<LookupOutcome> outcomes;
        try {
            outcomes = client.lookupMany(ids, Duration.ofMillis(100));
        } catch (QuotaExceededException e) {
            System.err.println("Monthly lookups used up: " + e.getMonthlyLookups());
            System.exit(3);
            return;
        } catch (AppBlockListException e) {
            System.err.println("Lookup failed (HTTP " + e.getStatusCode() + "): " + e.getMessage());
            System.exit(2);
            return;
        }

        for (LookupOutcome o : outcomes) {
            if (!o.isOk()) {
                System.out.printf("%-30s error   %s%n", o.getId(), o.getError().getMessage());
                continue;
            }
            AppInfo app = o.getApp();
            if (!app.isFound()) {
                System.out.printf("%-30s unknown %s%n", o.getId(), app.getMessage());
            } else if (app.inCategory(BLOCKED)) {
                System.out.printf("%-30s BLOCK   %s (%s, %s)%n", o.getId(), app.getName(),
                        app.getPrimaryCategory().orElse("?"), app.getAgeRating());
            } else {
                System.out.printf("%-30s allow   %s %s%n", o.getId(), app.getName(), app.getFilteringCategories());
            }
        }
    }
}
