package tw.trace.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;

/** Native Google sign-in: the system account picker gives a Google ID token. */
final class GoogleSignIn {
    private GoogleSignIn() {}

    static final String PACKAGE = "tw.trace.app";
    /** The fixed signature of this app; Firebase must know it for Google sign-in to work. */
    static final String SHA1 = "D1:DB:93:53:59:5B:1C:C8:89:8A:06:94:3C:7B:14:E6:3D:7B:30:66";

    interface Done {
        void ok(String idToken);
        void fail(String message, boolean canceled);
    }

    private static final String SETUP_HELP = "最常見的原因是 Firebase 專案還沒登錄這支 App：到 Firebase 主控台的專案設定，新增 Android 應用程式，填入套件名稱 "
            + PACKAGE + " 和 SHA-1 " + SHA1 + "，完成後等 5 分鐘再試。";

    static void start(Activity a, String clientId, final Done cb) {
        final long t0 = System.currentTimeMillis();
        final Handler main = new Handler(Looper.getMainLooper());
        Executor ex = new Executor() {
            @Override public void execute(Runnable r) { main.post(r); }
        };
        try {
            CredentialManager cm = CredentialManager.create(a);
            GetSignInWithGoogleOption opt = new GetSignInWithGoogleOption.Builder(clientId).build();
            GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(opt).build();
            cm.getCredentialAsync(a, req, null, ex, new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override public void onResult(GetCredentialResponse r) {
                    try {
                        Credential c = r.getCredential();
                        if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            cb.ok(GoogleIdTokenCredential.createFrom(((CustomCredential) c).getData()).getIdToken());
                        } else {
                            cb.fail("沒有拿到 Google 帳戶資料，請再試一次。", false);
                        }
                    } catch (Exception e) {
                        cb.fail("Google 登入資料解析失敗：" + e.getMessage(), false);
                    }
                }

                @Override public void onError(GetCredentialException e) {
                    if (e instanceof GetCredentialCancellationException) {
                        // A person takes seconds to cancel. Closing at once nearly always means the app is not registered.
                        if (System.currentTimeMillis() - t0 < 3000) cb.fail("登入視窗很快就被關閉。" + SETUP_HELP, false);
                        else cb.fail("已取消登入。", true);
                    } else {
                        cb.fail("Google 登入失敗。" + SETUP_HELP, false);
                    }
                }
            });
        } catch (Throwable t) {
            cb.fail("無法開啟 Google 登入：" + t.getMessage(), false);
        }
    }
}
