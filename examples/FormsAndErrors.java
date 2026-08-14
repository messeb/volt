import net.messeb.volt.BadRequest;
import net.messeb.volt.Cors;
import net.messeb.volt.Volt;

import java.util.Map;

public class FormsAndErrors {
    public static void main(String[] args) {
        Volt.port(8080)
                .cors(Cors.allowOrigin("https://app.example.com"))
                .before(context -> context.header("X-App", "forms"))
                .post("/signup", context -> {
                    String email = context.formParam("email");
                    if (email == null || !email.contains("@")) {
                        throw new BadRequest("email must contain '@' in this example");
                    }
                    context.status(201).json(Map.of("email", email));
                })
                .start();
    }
}
