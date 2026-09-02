package helium314.keyboard.compat

import android.content.res.Configuration
import java.util.Locale

fun Configuration.locale(): Locale = locales[0]
