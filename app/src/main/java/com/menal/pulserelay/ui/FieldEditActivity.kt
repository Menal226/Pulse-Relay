package com.menal.pulserelay.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.menal.pulserelay.R

/**
 * A small classic-View editor. Compose's embedded text fields on Wear OS did
 * not reliably update while the IME was open, so this screen edits a prefilled
 * value with a normal EditText and returns the complete string.
 */
class FieldEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_edit)

        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val initial = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)

        findViewById<TextView>(R.id.field_label).text = label
        val input = findViewById<EditText>(R.id.field_input)
        input.inputType = inputType
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setText(initial)
        input.setSelection(initial.length)
        input.requestFocus()

        findViewById<Button>(R.id.field_save).setOnClickListener { finishWithValue(input) }
        findViewById<Button>(R.id.field_cancel).setOnClickListener { finish() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishWithValue(input)
                true
            } else {
                false
            }
        }

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        input.postDelayed({
            val imm = getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun finishWithValue(input: EditText) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_TEXT, input.text.toString())
        )
        finish()
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_TEXT = "text"
        const val EXTRA_INPUT_TYPE = "input_type"
    }
}
