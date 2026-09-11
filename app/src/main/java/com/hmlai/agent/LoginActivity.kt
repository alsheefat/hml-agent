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
    private lateinit var callbackManager: CallbackManager
    private lateinit var progress: ProgressBar

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
                googleErrorMessage(e.statusCode),
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

        // This app performs local Google account sign-in only; it does not send the
        // Google ID token to a backend. Requesting an ID token forces the Google
        // Sign-In SDK to validate a Web/server OAuth client ID and can produce
        // DEVELOPER_ERROR when that server client is not configured exactly right.
        // Email/profile sign-in only requires the Android OAuth configuration
        // (package name + signing certificate SHA-1).
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        callbackManager = CallbackManager.Factory.create()
        LoginManager.getInstance()
            .registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
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

        findViewById<View>(R.id.googleButton).setOnClickListener {
            showProgress(true)
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        findViewById<View>(R.id.facebookButton).setOnClickListener {
            showProgress(true)
            LoginManager.getInstance()
                .logInWithReadPermissions(this, listOf("public_profile", "email"))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }

    private fun googleErrorMessage(statusCode: Int): String = when (statusCode) {
        10 -> "Google sign-in failed (DEVELOPER_ERROR). Check that the Web client ID, Android package name, and SHA-1 certificate belong to the same Google Cloud project."
        12500 -> "Google sign-in failed. The Google Play services configuration is invalid for this app."
        12501 -> "Google sign-in was cancelled."
        12502 -> "Google sign-in is already in progress."
        else -> "Google sign-in failed (code $statusCode). Check your Google OAuth configuration."
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
