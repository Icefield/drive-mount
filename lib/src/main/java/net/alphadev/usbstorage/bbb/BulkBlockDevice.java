/**
 * Copyright © 2014 Jan Seeger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.alphadev.usbstorage.bbb;

import net.alphadev.usbstorage.api.BlockDevice;
import net.alphadev.usbstorage.api.BulkDevice;
import net.alphadev.usbstorage.scsi.CommandBlockWrapper;
import net.alphadev.usbstorage.scsi.CommandStatusWrapper;
import net.alphadev.usbstorage.scsi.answer.ModeSenseResponse;
import net.alphadev.usbstorage.scsi.answer.ReadCapacityResponse;
import net.alphadev.usbstorage.scsi.answer.ReadFormatCapacitiesEntry;
import net.alphadev.usbstorage.scsi.answer.ReadFormatCapacitiesHeader;
import net.alphadev.usbstorage.scsi.answer.RequestSenseResponse;
import net.alphadev.usbstorage.scsi.answer.StandardInquiryAnswer;
import net.alphadev.usbstorage.scsi.command.Inquiry;
import net.alphadev.usbstorage.scsi.command.ModeSense;
import net.alphadev.usbstorage.scsi.command.Read10;
import net.alphadev.usbstorage.scsi.command.ReadCapacity;
import net.alphadev.usbstorage.scsi.command.ReadFormatCapacities;
import net.alphadev.usbstorage.scsi.command.RequestSense;
import net.alphadev.usbstorage.scsi.command.ScsiCommand;
import net.alphadev.usbstorage.scsi.command.TestUnitReady;

import java.io.IOException;
import java.nio.ByteBuffer;

import de.waldheinz.fs.ReadOnlyException;

/**
 * @author Jan Seeger <jan@alphadev.net>
 */
public class BulkBlockDevice implements BlockDevice {
    private final BulkDevice mAbstractBulkDevice;
    private final byte mLunToUse;
    /**
     * 512 KB max transfer unit.
     */
    private final int mMaxTransferSize = 512 * 1024;
    private long mDeviceBoundaries;
    private int mBlockSize = 512;

    public BulkBlockDevice(BulkDevice usbBlockDevice) {
        mAbstractBulkDevice = usbBlockDevice;

        inquireDevice();
        testUnitReady();
        acquireDriveCapacity();
        senseMode();
        testUnitReady();
        mLunToUse = 0;
    }

    private void senseMode() {
        final ModeSense cmd = new ModeSense();
        cmd.setDisableBlockDescriptor(false);
        cmd.setPageControl(ModeSense.PageControlValues.Current);
        cmd.setPageCode((byte) 0x3f);
        cmd.setSubPageCode((byte) 0);
        send_mass_storage_command(cmd);

        byte[] data = mAbstractBulkDevice.read(cmd.getExpectedAnswerLength());
        new ModeSenseResponse(data);

        assumeDeviceStatusOK();
    }

    private void testUnitReady() {
        CommandStatusWrapper csw;
        do {
            send_mass_storage_command(new TestUnitReady());
            csw = getDeviceStatus();
        }
        while (csw.getStatus() == CommandStatusWrapper.Status.BUSY);
        assumeDeviceStatusOK(csw);
    }

    @SuppressWarnings("unused")
    private void acquireCardCapacities() {
        try {
            send_mass_storage_command(new ReadFormatCapacities());
            byte[] answer = mAbstractBulkDevice.read(ReadFormatCapacitiesHeader.LENGTH);
            ReadFormatCapacitiesHeader capacity = new ReadFormatCapacitiesHeader(answer);
            assumeDeviceStatusOK();

            for (int i = 0; i < capacity.getCapacityEntryCount(); i++) {
                byte[] capacityData = mAbstractBulkDevice.read(ReadFormatCapacitiesEntry.LENGTH);
                new ReadFormatCapacitiesEntry(capacityData);
            }

            // determine the last addressable block
            if (capacity.getNumberOfBlocks() != 0) {
                mDeviceBoundaries = capacity.getNumberOfBlocks();
            }

            if (capacity.getBlockLength() != 0) {
                mBlockSize = capacity.getBlockLength();
            }
        } catch (IllegalArgumentException ex) {
            // do nothing as the read format capacities command is optional.
        }
    }

    private void acquireDriveCapacity() {
        try {
            send_mass_storage_command(new ReadCapacity());
            byte[] answer = mAbstractBulkDevice.read(ReadCapacityResponse.LENGTH);
            ReadCapacityResponse capacity = new ReadCapacityResponse(answer);

            assumeDeviceStatusOK();

            mDeviceBoundaries = capacity.getNumberOfBlocks();
            mBlockSize = capacity.getBlockSize();
        } catch (IllegalArgumentException e) {
            // whaaat!
        }
    }

    private void assumeDeviceStatusOK() {
        assumeDeviceStatusOK(getDeviceStatus());
    }

    private void assumeDeviceStatusOK(CommandStatusWrapper csw) {
        if (CommandStatusWrapper.Status.COMMAND_PASSED != csw.getStatus()) {
            throw new IllegalStateException("device signaled error state!");
        }
    }

    @SuppressWarnings("unused")
    private void checkErrorCondition() {
        send_mass_storage_command(new RequestSense());
        byte[] answer = mAbstractBulkDevice.read(RequestSenseResponse.LENGTH + 10);
        new RequestSenseResponse(answer);
    }

    private CommandStatusWrapper getDeviceStatus() {
        byte[] buffer = mAbstractBulkDevice.read(13);
        return new CommandStatusWrapper(buffer);
    }

    private void inquireDevice() {
        send_mass_storage_command(new Inquiry());

        byte[] answer = mAbstractBulkDevice.read(StandardInquiryAnswer.LENGTH);
        new StandardInquiryAnswer(answer);

        assumeDeviceStatusOK();
    }

    @Override
    public long getSize() throws IOException {
        /*
         * read during device setup.
         * won't change during the mount time.
         */
        return mDeviceBoundaries;
    }

    @Override
    public void read(long offset, ByteBuffer buffer) {
        final int totalRequestSize = buffer.limit();
        int remainingBytes = buffer.limit();

        System.out.printf("reading %d bytes, offset %d%n", totalRequestSize, offset);
        while (remainingBytes > 0) {
            final int requestSize = Math.min(remainingBytes, mMaxTransferSize);
            final int sectors = (int) Math.ceil(requestSize / mBlockSize);
            remainingBytes -= requestSize;

            final Read10 cmd = new Read10();
            cmd.setOffset(offset);
            cmd.setTransferLength((short) sectors);
            cmd.setExpectedAnswerLength(requestSize);
            send_mass_storage_command(cmd);

            for (int subRequest = 0; subRequest < sectors; subRequest++) {
                final int overLength = requestSize % mBlockSize;
                final byte[] buf = mAbstractBulkDevice.read(mBlockSize);
                buffer.put(buf, 0, mBlockSize - overLength);
            }

            assumeDeviceStatusOK();
            System.out.printf("  of size %d%n", requestSize);
        }
    }

    private int send_mass_storage_command(ScsiCommand command) {
        final CommandBlockWrapper cbw = new CommandBlockWrapper();
        cbw.setFlags(command.getDirection());
        cbw.setLun(mLunToUse);
        cbw.setCommand(command);

        return mAbstractBulkDevice.write(cbw);
    }

    @Override
    public void write(long offset, ByteBuffer byteBuffer) throws ReadOnlyException, IllegalArgumentException {

    }

    @Override
    public void flush() {
        // since we write everything directly to the device there's no need to flush.
    }

    @Override
    public int getSectorSize() {
        return mBlockSize;
    }

    @Override
    public void close() throws IOException {
        mAbstractBulkDevice.close();
    }

    @Override
    public boolean isClosed() {
        return mAbstractBulkDevice.isClosed();
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getId() {
        return mAbstractBulkDevice.getId();
    }
}
