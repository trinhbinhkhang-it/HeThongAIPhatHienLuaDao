package com.voiceshield.ai

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * The personal-information page, reached from the top-right person icon on Page 1.
 *
 * It holds one field — the user's name — which the Discord alert reports as "Tên người dùng".
 * Kept deliberately minimal: only the name is used anywhere, so the page asks for nothing the
 * app does not need. The value lives in [ProtectionState] beside the other settings, so it
 * survives process death and is read at alert time with no listener.
 */
class PersonalInfoActivity : AppCompatActivity() {

    private lateinit var nameInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personal_info)

        nameInput = findViewById(R.id.input_user_name)
        nameInput.setText(ProtectionState.getUserName(this))

        findViewById<MaterialButton>(R.id.btn_profile_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_profile_back).setOnClickListener { finish() }
    }

    private fun save() {
        ProtectionState.setUserName(this, nameInput.text?.toString().orEmpty())
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
    }
}
