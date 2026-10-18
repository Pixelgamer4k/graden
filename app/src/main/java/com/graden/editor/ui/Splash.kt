package com.graden.editor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graden.editor.EditorViewModel

/** Shown at launch while the app gets ready (shaders, the project list, the first covers), so what follows is smooth. */
@Composable
internal fun Splash(vm: EditorViewModel) {
    val p by animateFloatAsState(vm.startupProgress, tween(260), label = "startup")
    Box(Modifier.fillMaxSize().background(Palette.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Graden", color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Getting everything ready", color = Palette.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(26.dp))
            Box(Modifier.width(180.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF22232A))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(Palette.Accent.copy(alpha = 0.7f), Palette.Accent))))
            }
        }
        Text("Pixel", color = Palette.TextDim.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
    }
}
