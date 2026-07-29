package com.lingmiao.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lingmiao.service.LicenseManager

/**
 * 激活页面
 * 
 * 首次启动需要输入激活码
 * 激活码通过服务器验证（离线版用本地校验）
 */
class ActivationActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xFF1A1A2E.toInt())
        }
        
        // Logo区域
        layout.addView(android.widget.TextView(this).apply {
            text = "🐱"
            textSize = 72f
        })
        
        layout.addView(android.widget.TextView(this).apply {
            text = "灵喵 LingMiao"
            textSize = 32f
            setTextColor(0xFF00E5FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        })
        
        layout.addView(android.widget.TextView(this).apply {
            text = "智能台球辅助系统"
            textSize = 16f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 48)
        })
        
        // 激活码输入
        layout.addView(android.widget.TextView(this).apply {
            text = "请输入激活码"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        })
        
        val input = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(19))
        }
        layout.addView(input)
        
        // 激活按钮
        val activateBtn = Button(this).apply {
            text = "激活"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF00BCD4.toInt())
            setPadding(48, 16, 48, 16)
            setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    Toast.makeText(this@ActivationActivity, "请输入激活码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (LicenseManager.verify(this@ActivationActivity, code)) {
                    // 激活成功
                    getSharedPreferences("lingmiao", MODE_PRIVATE)
                        .edit()
                        .putBoolean("activated", true)
                        .putString("license", code)
                        .apply()
                    
                    Toast.makeText(this@ActivationActivity, "激活成功！", Toast.LENGTH_LONG).show()
                    
                    // 进入主程序
                    startActivity(Intent(this@ActivationActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@ActivationActivity, "激活码无效", Toast.LENGTH_LONG).show()
                }
            }
        }
        layout.addView(activateBtn)
        
        // 试用按钮
        val trialBtn = Button(this).apply {
            text = "试用7天"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                LicenseManager.startTrial(this@ActivationActivity)
                startActivity(Intent(this@ActivationActivity, MainActivity::class.java))
                finish()
            }
        }
        layout.addView(trialBtn)
        
        setContentView(layout)
    }
}
