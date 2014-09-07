package net.alphadev.usbstorage.bbb;

import net.alphadev.usbstorage.api.BulkDevice;
import net.alphadev.usbstorage.scsi.answer.ReadCapacityResponse;
import net.alphadev.usbstorage.scsi.answer.ReadFormatCapacitiesEntry;
import net.alphadev.usbstorage.scsi.answer.ReadFormatCapacitiesHeader;
import net.alphadev.usbstorage.scsi.answer.StandardInquiryAnswer;
import net.alphadev.usbstorage.scsi.command.Inquiry;
import net.alphadev.usbstorage.scsi.command.ReadCapacity;
import net.alphadev.usbstorage.scsi.command.ReadFormatCapacities;
import net.alphadev.usbstorage.scsi.command.ScsiCommand;
import net.alphadev.usbstorage.scsi.command.TestUnitReady;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

import de.waldheinz.fs.BlockDevice;
import de.waldheinz.fs.ReadOnlyException;

/**
 * @author Jan Seeger <jan@alphadev.net>
 */
public class BulkBlockDevice implements BlockDevice, Closeable {
    private BulkDevice mAbstractBulkDevice;
    private long mDeviceBoundaries;
    private int mBlockSize = 512;
    private byte mLunToUse = 0;

    public BulkBlockDevice(BulkDevice usbBlockDevice) throws IOException {
        mAbstractBulkDevice = usbBlockDevice;

        setupInquiryPhase();
        setupCapacityPhase();
        testUnitReady();
    }

    private void testUnitReady() throws IOException {
        send_mass_storage_command(new TestUnitReady());

        checkDeviceStatus();
    }

    private void setupCapacityPhase() throws IOException {
        try {
            send_mass_storage_command(new ReadFormatCapacities());
            byte[] answer = mAbstractBulkDevice.retrieve_data_packet(ReadFormatCapacitiesHeader.LENGTH);
            ReadFormatCapacitiesHeader capacity = new ReadFormatCapacitiesHeader(answer);
            checkDeviceStatus();

            for (int i = 0; i < capacity.getCapacityEntryCount(); i++) {
                byte[] capacityData = mAbstractBulkDevice.retrieve_data_packet(ReadFormatCapacitiesEntry.LENGTH);
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

        try {
            send_mass_storage_command(new ReadCapacity());
            byte[] answer = mAbstractBulkDevice.retrieve_data_packet(ReadCapacityResponse.LENGTH);
            ReadCapacityResponse capacity = new ReadCapacityResponse(answer);

            checkDeviceStatus();

            mDeviceBoundaries = capacity.getNumberOfBlocks();
            mBlockSize = capacity.getBlockSize();
        } catch (IllegalArgumentException e) {
            // whaaat!
        }
    }

    private void checkDeviceStatus() {
        CommandStatusWrapper csw = retrieve_mass_storage_answer();
        if (CommandStatusWrapper.Status.COMMAND_PASSED != csw.getStatus()) {
            throw new IllegalStateException("device signaled error state!");
        }
    }

    private void setupInquiryPhase() throws IOException {
        send_mass_storage_command(new Inquiry());

        byte[] answer = mAbstractBulkDevice.retrieve_data_packet(StandardInquiryAnswer.LENGTH);
        new StandardInquiryAnswer(answer);

        checkDeviceStatus();
    }

    private CommandStatusWrapper retrieve_mass_storage_answer() {
        byte[] buffer = mAbstractBulkDevice.retrieve_data_packet(13);
        return new CommandStatusWrapper(buffer);
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
    public void read(long offset, ByteBuffer byteBuffer) throws IOException {
    }

    private int send_mass_storage_command(ScsiCommand command) throws IOException {
        CommandBlockWrapper cbw = new CommandBlockWrapper();
        cbw.setFlags(command.getDirection());
        cbw.setLun(mLunToUse);
        cbw.setCommand(command);

        return mAbstractBulkDevice.send_mass_storage_command(cbw);
    }

    @Override
    public void write(long offset, ByteBuffer byteBuffer) throws ReadOnlyException, IOException, IllegalArgumentException {

    }

    @Override
    public void flush() throws IOException {
        // since we write everything directly to the device there's no need to flush.
    }

    @Override
    public int getSectorSize() throws IOException {
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
}