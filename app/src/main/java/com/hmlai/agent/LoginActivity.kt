package com.hmlai.agent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.Profile
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * NOTE: the buttons here call the real Google Sign-In and Facebook Login SDKs, but they
 * can't actually complete a sign-in until you plug in your own credentials:
 *  - res/values/strings.xml -> default_web_client_id  (Google Cloud Console OAuth client)
 *  - res/values/strings.xml -> facebook_app_id / facebook_client_token (developers.facebook.com)
 * Until those are filled in, tapping the buttons will fail with a clear error rather than
 * silently pretending to succeed.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var callbackManager: CallbackManager? = null
    private lateinit var progress: ProgressBar
    private var facebookConfigured = true

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            onLoginSuccess(account.displayName ?: "Google user", account.email ?: "Signed in with Google")
        } catch (e: ApiException) {
            showProgress(false)
            Toast.makeText(
                this,
                "Google sign-in failed (code ${e.statusCode}). Check default_web_client_id in strings.xml.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if (SessionManager.isLoggedIn(this)) {
            goToMain()
            return
        }

        progress = findViewById(R.id.loginProgress)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Facebook's SDK can throw at init time if the App ID/Client Token
        // are still placeholders — catch that so it disables Facebook
        // login gracefully instead of crashing the whole app on launch.
        val facebookAppId = getString(R.string.facebook_app_id)
        facebookConfigured = !facebookAppId.startsWith("REPLACE_WITH")

        if (facebookConfigured) {
            try {
                callbackManager = CallbackManager.Factory.create()
                LoginManager.getInstance()
                    .registerCallback(callbackManager!!, object : FacebookCallback<LoginResult> {
                        override fun onSuccess(result: LoginResult) {
                            val profile = Profile.getCurrentProfile()
                            onLoginSuccess(profile?.name ?: "Facebook user", "Signed in with Facebook")
                        }

                        override fun onCancel() {
                            showProgress(false)
                        }

                        override fun onError(error: FacebookException) {
                            showProgress(false)
                            Toast.makeText(
                                this@LoginActivity,
                                "Facebook sign-in failed. Check facebook_app_id in strings.xml.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
            } catch (e: Exception) {
                facebookConfigured = false
            }
        }

        findViewById<View>(R.id.googleButton).setOnClickListener {
            showProgress(true)
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        findViewById<View>(R.id.facebookButton).setOnClickListener {
            if (!facebookConfigured) {
                Toast.makeText(
                    this,
                    "Facebook login isn't set up yet — add a real facebook_app_id in strings.xml.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                showProgress(true)
                LoginManager.getInstance()
                    .logInWithReadPermissions(this, listOf("public_profile", "email"))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager?.onActivityResult(requestCode, resultCode, data)
    }

    private fun onLoginSuccess(name: String, subtitle: String) {
        SessionManager.saveSession(this, name, subtitle)
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showProgress(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }
}
