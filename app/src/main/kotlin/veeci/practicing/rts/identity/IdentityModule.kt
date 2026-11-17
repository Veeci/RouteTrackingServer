package veeci.practicing.rts.identity

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import veeci.practicing.rts.identity.adapter.out.persistence.ExposedIdentityEventOutbox
import veeci.practicing.rts.identity.adapter.out.persistence.ExposedUserRepository
import veeci.practicing.rts.identity.application.DevTokenService
import veeci.practicing.rts.identity.application.DriverAdminService
import veeci.practicing.rts.identity.application.api.DriverAccounts
import veeci.practicing.rts.identity.application.port.out.IdentityEventOutbox
import veeci.practicing.rts.identity.application.port.out.UserRepository

/** The identity context's object graph: users, the dev token service, driver accounts and their events. */
fun identityModule(): Module =
    module {
        single<UserRepository> { ExposedUserRepository() }
        single<IdentityEventOutbox> { ExposedIdentityEventOutbox(get()) }
        single { DevTokenService(get(), get(), get(), get(), get()) }
        single { DriverAdminService(get(), get(), get(), get(), get(), get()) } bind DriverAccounts::class
    }
