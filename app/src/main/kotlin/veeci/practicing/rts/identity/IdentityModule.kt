package veeci.practicing.rts.identity

import org.koin.core.module.Module
import org.koin.dsl.module
import veeci.practicing.rts.identity.adapter.out.persistence.ExposedUserRepository
import veeci.practicing.rts.identity.application.DevTokenService
import veeci.practicing.rts.identity.application.port.out.UserRepository

/** The identity context's object graph: users and the dev token service. */
fun identityModule(): Module =
    module {
        single<UserRepository> { ExposedUserRepository() }
        single { DevTokenService(get(), get(), get()) }
    }
