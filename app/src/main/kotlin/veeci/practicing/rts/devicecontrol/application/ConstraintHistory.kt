package veeci.practicing.rts.devicecontrol.application

import veeci.practicing.rts.devicecontrol.application.api.AppliedConstraint
import veeci.practicing.rts.devicecontrol.application.api.AppliedConstraints
import veeci.practicing.rts.devicecontrol.application.port.out.DeviceCommandRepository
import veeci.practicing.rts.devicecontrol.domain.CommandPayload
import veeci.practicing.rts.platform.db.TransactionRunner
import veeci.practicing.rts.shared.UserId
import kotlin.time.Instant

class ConstraintHistory(
    private val commands: DeviceCommandRepository,
    private val transactions: TransactionRunner,
) : AppliedConstraints {
    override suspend fun history(
        driver: UserId,
        from: Instant,
        to: Instant,
    ): List<AppliedConstraint> =
        transactions.inTransaction { commands.appliedConstraints(driver, from, to) }.mapNotNull { command ->
            (command.payload as? CommandPayload.SetConstraint)?.let { AppliedConstraint(command.updatedAt, it.minIntervalMs) }
        }
}
