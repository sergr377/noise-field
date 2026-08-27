package dev.noisefield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.noisefield.ui.calibration.CalibrationScreen
import dev.noisefield.ui.common.PermissionGate
import dev.noisefield.ui.measure.MeasureScreen
import dev.noisefield.ui.point.PointCardScreen
import dev.noisefield.ui.theme.NoiseTheme
import dev.noisefield.ui.theme.Palette
import dev.noisefield.ui.trip.TripScreen

/** Маршруты. Четыре экрана, стартовый — «Выезд» (§1). */
object Routes {
    const val TRIP = "trip"
    const val MEASURE = "measure"
    const val CALIBRATION = "calibration"
    const val POINT = "point"
    fun point(id: Long) = "$POINT/$id"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        setContent {
            NoiseTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Paper)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    NoiseNav()
                }
            }
        }
    }
}

@Composable
private fun NoiseNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.TRIP) {

        composable(Routes.TRIP) {
            TripScreen(
                onNewMeasurement = { nav.navigate(Routes.MEASURE) },
                onCalibration = { nav.navigate(Routes.CALIBRATION) },
                onOpenPoint = { id -> nav.navigate(Routes.point(id)) },
            )
        }

        composable(Routes.MEASURE) {
            // Разрешения запрашиваются при первом входе именно сюда (§1).
            PermissionGate {
                MeasureScreen(
                    onFinished = { id ->
                        nav.navigate(Routes.point(id)) {
                            popUpTo(Routes.TRIP)
                        }
                    },
                    onCalibration = { nav.navigate(Routes.CALIBRATION) },
                    onBack = { nav.popBackStack() },
                )
            }
        }

        composable(Routes.CALIBRATION) {
            PermissionGate {
                CalibrationScreen(onDone = { nav.popBackStack() })
            }
        }

        composable(
            route = Routes.POINT + "/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            PointCardScreen(
                measurementId = entry.arguments?.getLong("id") ?: 0L,
                onDone = {
                    nav.navigate(Routes.TRIP) {
                        popUpTo(Routes.TRIP) { inclusive = true }
                    }
                },
            )
        }
    }
}
