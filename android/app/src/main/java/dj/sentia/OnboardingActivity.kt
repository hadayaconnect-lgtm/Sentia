package dj.sentia

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Accueil : un seul choix (profil d'accessibilité), puis un message de confidentialité clair. */
class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        val map = mapOf(R.id.pBlind to "blind", R.id.pDeaf to "deaf", R.id.pBoth to "both", R.id.pOther to "other")
        for ((id, profile) in map) {
            findViewById<Button>(id).setOnClickListener { choose(profile) }
        }
    }

    private fun choose(profile: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ob_title)
            .setMessage(R.string.ob_privacy)
            .setCancelable(false)
            .setPositiveButton(R.string.ob_accept) { _, _ ->
                settings.profile = profile
                // Valeurs par défaut utiles : réponses parlées sauf pour les personnes sourdes.
                settings.voiceReplies = profile != "deaf"
                startActivity(Intent(this, AssistantActivity::class.java).putExtra(AssistantActivity.EXTRA_WAKE, profile == "blind" || profile == "both"))
                finish()
            }
            .show()
    }
}
