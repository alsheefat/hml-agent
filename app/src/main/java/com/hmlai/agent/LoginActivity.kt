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
 * Google/Facebook sign-in kept failing to complete (OAuth console config, invalid
 * App ID, etc.), so both buttons are now hidden in activity_login.xml — the code
 * below is untouched and still correctly wired, so re-enabling either one later is
 * just a matter of flipping its visibility back on and filling in real credentials:
 *  - res/values/strings.xml -> default_web_client_id  (Google Cloud Console OAuth client)
 *  - res/values/strings.xml -> facebook_app_id / facebook_client_token (developers.facebook.com)
 * For now, "Continue without signing in" is the actual way into the app.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var callbackManager: CallbackManager
    private lateinit var progress: ProgressBar

    // Set when this activity was opened via a pinned Home-screen shortcut for a
    // specific conversation (LoginActivity is the exported/launcher activity, so
    // shortcuts route through here first, then get forwarded on to MainActivity).
    private var pendingConversationId: String? = null

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

        pendingConversationId = intent.getStringExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID)

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

        findViewById<View>(R.id.skipLoginButton).setOnClickListener {
            SessionManager.saveSession(this, "Guest", "Signed in later")
            goToMain()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }

    private fun onLoginSuccess(name: String, subtitle: String) {
        SessionManager.saveSession(this, name, subtitle)
        goToMain()
    }

    private fun goToMain() {
        val mainIntent = Intent(this, MainActivity::class.java)
        pendingConversationId?.let { mainIntent.putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID, it) }
        startActivity(mainIntent)
        finish()
    }

    private fun showProgress(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }
}
