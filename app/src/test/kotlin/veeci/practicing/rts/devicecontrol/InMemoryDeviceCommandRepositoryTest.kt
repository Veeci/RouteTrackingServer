package veeci.practicing.rts.devicecontrol

import veeci.practicing.rts.testing.devicecontrol.DeviceCommandRepositoryContract
import veeci.practicing.rts.testing.devicecontrol.InMemoryDeviceCommandRepository

class InMemoryDeviceCommandRepositoryTest : DeviceCommandRepositoryContract() {
    override val commands = InMemoryDeviceCommandRepository()
}
