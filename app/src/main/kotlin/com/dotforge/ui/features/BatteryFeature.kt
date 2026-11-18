package com.dotforge.ui.features

import androidx.compose.runtime.Composable
import com.dotforge.ui.Nav
import com.dotforge.ui.Setup

/** Features > Battery. Not built yet: the shared layout with its first line. */
@Composable
fun BatteryFeature(nav: Nav, setup: Setup) = FeatureLayout(Feature.BATTERY, nav)
