package com.hmlai.agent

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = "HML Agent\n\nStage 1 build — chat UI coming next."
        textView.textSize = 20f
        textView.setPadding(48, 96, 48, 48)

        setContentView(textView)
    }
}
