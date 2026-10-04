package veeci.practicing.rts.platform.di

import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Clock

/** Technical singletons every context can use. Later steps add the DataSource, TransactionRunner and health checks. */
fun platformModule(): Module =
    module {
        single<Clock> { Clock.systemUTC() }
    }
