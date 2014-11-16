package net.alphadev.usbstorage.scsi.command;

/**
 * @author Jan Seeger <jan@alphadev.net>
 */
public class TestUnitReady extends ScsiCommand {
    @Override
    public byte[] asBytes() {
        // all zero since even opcode == 0x0
        return new byte[6];
    }

    @Override
    public int getExpectedAnswerLength() {
        return 0;
    }
}
