package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Isolated debug-only registration; no real credentials or production endpoint. */
public class KiraziumRegisterFragment extends Fragment {
    public static final String TAG = "KIRAZIUM_REGISTER_FRAGMENT";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern TEST_ORIGIN = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final String TEST_CONFIG_URL = "https://raw.githubusercontent.com/skynecos/KiraziumClient/"
            + "test/kirazium-account-registration/.github/account-registration-test-endpoint.txt";
    private final ExecutorService requests = Executors.newSingleThreadExecutor();

    public KiraziumRegisterFragment() {
        super(R.layout.fragment_kirazium_register);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        EditText username = root.findViewById(R.id.kirazium_register_username);
        EditText email = root.findViewById(R.id.kirazium_register_email);
        EditText password = root.findViewById(R.id.kirazium_register_password);
        TextView status = root.findViewById(R.id.kirazium_register_status);
        Button check = root.findViewById(R.id.kirazium_register_check);
        Button register = root.findViewById(R.id.kirazium_register_submit);

        check.setOnClickListener(v -> submit(root, username, email, password, status, check, register, false));
        register.setOnClickListener(v -> submit(root, username, email, password, status, check, register, true));
    }

    private void submit(View root, EditText nameInput, EditText emailInput, EditText passwordInput,
                        TextView status, Button check, Button register, boolean create) {
        if (!BuildConfig.DEBUG) {
            status.setText(R.string.kirazium_account_not_configured);
            return;
        }
        String name = nameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (!NAME.matcher(name).matches() || email.length() > 254 || !EMAIL.matcher(email).matches()
                || (create && (password.length() < 5 || password.length() > 30))) {
            status.setText(R.string.kirazium_account_invalid_input);
            return;
        }
        check.setEnabled(false);
        register.setEnabled(false);
        status.setText(create ? R.string.kirazium_account_registering : R.string.kirazium_account_checking);
        requests.execute(() -> {
            int message;
            boolean created = false;
            try {
                String origin = resolveTestOrigin();
                if (create) {
                    ApiResult result = post(origin, "/v1/accounts", new JSONObject()
                            .put("username", name).put("email", email).put("password", password));
                    message = errorMessage(result);
                    created = result.status == 201;
                    if (created) message = R.string.kirazium_account_registered;
                } else {
                    ApiResult nameCheck = post(origin, "/v1/usernames/check", new JSONObject().put("username", name));
                    ApiResult emailCheck = post(origin, "/v1/emails/check", new JSONObject().put("email", email));
                    if (nameCheck.status != 200 || emailCheck.status != 200) {
                        message = R.string.kirazium_account_unavailable;
                    } else if (!nameCheck.body.optBoolean("available", false)) {
                        message = R.string.kirazium_account_username_taken;
                    } else if (!emailCheck.body.optBoolean("available", false)) {
                        message = R.string.kirazium_account_email_taken;
                    } else {
                        message = R.string.kirazium_account_available;
                    }
                }
            } catch (Exception ignored) {
                message = R.string.kirazium_account_unavailable;
            }
            final int resultMessage = message;
            final boolean resultCreated = created;
            root.post(() -> {
                if (!isAdded() || getView() != root) return;
                passwordInput.setText("");
                status.setText(resultMessage);
                check.setEnabled(true);
                register.setEnabled(true);
                if (resultCreated) {
                    ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{name, ""});
                    Tools.swapFragment(requireActivity(), MainMenuFragment.class, MainMenuFragment.TAG, null);
                }
            });
        });
    }

    private static int errorMessage(ApiResult result) {
        if (result.status == 409) {
            String error = result.body.optString("error");
            if ("username_taken".equals(error)) return R.string.kirazium_account_username_taken;
            if ("email_taken".equals(error)) return R.string.kirazium_account_email_taken;
        }
        return R.string.kirazium_account_unavailable;
    }

    private static String resolveTestOrigin() throws IOException {
        String configured = BuildConfig.KIRAZIUM_ACCOUNT_API_BASE_URL.replaceAll("/$", "");
        if (!configured.isEmpty()) return configured;
        HttpURLConnection connection = (HttpURLConnection) new URL(TEST_CONFIG_URL).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Test endpoint unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[128];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (data.size() + count > 256) throw new IOException("Test endpoint response too large");
                    data.write(chunk, 0, count);
                }
                String origin = data.toString("UTF-8").trim();
                if (!TEST_ORIGIN.matcher(origin).matches()) throw new IOException("No active test endpoint");
                return origin;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static ApiResult post(String origin, String path, JSONObject body) throws IOException, JSONException {
        URL url = new URL(origin + path);
        if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return new ApiResult(status, new JSONObject());
            try (InputStream stream = input; ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[1024];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    if (data.size() + count > 4096) throw new IOException("Response too large");
                    data.write(chunk, 0, count);
                }
                return new ApiResult(status, new JSONObject(data.toString("UTF-8")));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static final class ApiResult {
        final int status;
        final JSONObject body;

        ApiResult(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    @Override
    public void onDestroy() {
        requests.shutdownNow();
        super.onDestroy();
    }
}
