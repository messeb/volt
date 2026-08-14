import net.messeb.volt.Auth;
import net.messeb.volt.Env;
import net.messeb.volt.Volt;

record Greeting(String message) {
}

public class QuickStart {
    public static void main(String[] args) {
        String expectedKey = Env.get("API_KEY");
        Volt.port(Env.get("PORT", 8080))
                .apiKey("X-Api-Key", key -> Auth.constantTimeEquals(key, expectedKey))
                .get("/health", context -> context.text("ok"), Auth.none())
                .get("/hello/{name}", context -> context.json(
                        new Greeting("Hello, " + context.pathRequired("name"))))
                .start();
    }
}
