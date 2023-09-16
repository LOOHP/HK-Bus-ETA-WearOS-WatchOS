package com.loohp.hkbuseta.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.loohp.hkbuseta.presentation.shared.Shared
import com.loohp.hkbuseta.presentation.theme.HKBusETATheme
import com.loohp.hkbuseta.presentation.utils.StringUtils


class FatalErrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shared.currentActivityClass = javaClass

        val zh: String?
        val en: String?
        if (intent.extras == null) {
            zh = null
            en = null
        } else {
            zh = intent.extras!!.getString("zh")
            en = intent.extras!!.getString("en")
        }

        setContent {
            Message(this, zh, en)
        }
    }
}

@Composable
fun Message(instance: FatalErrorActivity, zh: String?, en: String?) {
    HKBusETATheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            verticalArrangement = Arrangement.Top
        ) {
            Shared.MainTime()
        }
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 0.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary,
                text = zh ?: "Fatal Error Occurred"
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 0.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary,
                text = en ?: "發生錯誤"
            )
            Spacer(modifier = Modifier.size(StringUtils.scaledSize(10, instance).dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    modifier = Modifier
                        .width(StringUtils.scaledSize(50, instance).dp)
                        .height(StringUtils.scaledSize(50, instance).dp),
                    onClick = {
                        instance.startActivity(Intent(instance, MainActivity::class.java))
                        instance.finish()
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.secondary,
                        contentColor = MaterialTheme.colors.primary
                    ),
                    content = {
                        Icon(
                            modifier = Modifier.size(35.dp),
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = if (Shared.language == "en") "Relaunch App" else "重新載入",
                            tint = Color.Yellow,
                        )
                    }
                )
                Spacer(modifier = Modifier.size(StringUtils.scaledSize(25, instance).dp))
                Button(
                    modifier = Modifier
                        .width(StringUtils.scaledSize(50, instance).dp)
                        .height(StringUtils.scaledSize(50, instance).dp),
                    onClick = {
                        instance.finishAffinity()
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.secondary,
                        contentColor = MaterialTheme.colors.primary
                    ),
                    content = {
                        Icon(
                            modifier = Modifier.size(StringUtils.scaledSize(25, instance).dp),
                            imageVector = Icons.Filled.Close,
                            contentDescription = if (Shared.language == "en") "Exit App" else "退出應用程式",
                            tint = Color.Red,
                        )
                    }
                )
            }
        }
    }
}