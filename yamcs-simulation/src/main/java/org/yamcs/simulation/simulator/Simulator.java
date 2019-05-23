package org.yamcs.simulation.simulator;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.cfdp.DataFile;
import org.yamcs.cfdp.DataFileSegment;
import org.yamcs.cfdp.FileDirective;
import org.yamcs.cfdp.pdu.AckPacket;
import org.yamcs.cfdp.pdu.AckPacket.FileDirectiveSubtypeCode;
import org.yamcs.cfdp.pdu.AckPacket.TransactionStatus;
import org.yamcs.cfdp.pdu.CfdpHeader;
import org.yamcs.cfdp.pdu.CfdpPacket;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.cfdp.pdu.EofPacket;
import org.yamcs.cfdp.pdu.FileDataPacket;
import org.yamcs.cfdp.pdu.FileDirectiveCode;
import org.yamcs.cfdp.pdu.FileStoreResponse;
import org.yamcs.cfdp.pdu.FinishedPacket;
import org.yamcs.cfdp.pdu.FinishedPacket.FileStatus;
import org.yamcs.cfdp.pdu.MetadataPacket;
import org.yamcs.cfdp.pdu.NakPacket;
import org.yamcs.cfdp.pdu.SegmentRequest;
import org.yamcs.tctm.ErrorDetectionWordCalculator;
import org.yamcs.tctm.ccsds.error.CrcCciitCalculator;
import org.yamcs.utils.ByteArrayUtils;

import com.google.common.util.concurrent.AbstractService;

public class Simulator extends AbstractService {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    // no more than 100 pending commands
    protected BlockingQueue<CCSDSPacket> pendingCommands = new ArrayBlockingQueue<>(100);

    static int DEFAULT_MAX_LENGTH = 65542;
    int maxLength = DEFAULT_MAX_LENGTH;
    private TmTcLink tmLink;
    private TmTcLink tm2Link;
    private UdpLink cfdpLink;
    private TmTcLink losLink;
    private UdpFrameLink frameLink;

    private boolean los;
    private Date lastLosStart;
    private Date lastLosStop;
    private LosRecorder losRecorder;

    FlightDataHandler flightDataHandler;
    DHSHandler dhsHandler;
    PowerHandler powerDataHandler;
    RCSHandler rcsHandler;
    EpsLvpduHandler epslvpduHandler;

    private DataFile cfdpDataFile = null;
    List<SegmentRequest> missingSegments;

    int tmCycle = 0;
    AtomicInteger tm2SeqCount = new AtomicInteger(0);
    ErrorDetectionWordCalculator edwc2 = new CrcCciitCalculator();

    ScheduledThreadPoolExecutor executor;

    public Simulator(File dataDir, int tmPort, int tcPort, int losPort) {
        losRecorder = new LosRecorder(dataDir);
        powerDataHandler = new PowerHandler();
        rcsHandler = new RCSHandler();
        epslvpduHandler = new EpsLvpduHandler();
        flightDataHandler = new FlightDataHandler();
        dhsHandler = new DHSHandler();
    }

    /**
     * this runs in a separate thread but pushes commands to the main TM thread
     */
    public LosRecorder getLosDataRecorder() {
        return losRecorder;
    }

    public boolean isLOS() {
        return los;
    }

    public Date getLastLosStart() {
        return lastLosStart;
    }

    public Date getLastLosStop() {
        return lastLosStop;
    }

    public void setAOS() {
        if (los) {
            los = false;
            lastLosStop = new Date();
            losRecorder.stopRecording();
        }
    }

    public void setLOS() {
        if (!los) {
            los = true;
            lastLosStart = new Date();
            losRecorder.startRecording(lastLosStart);
        }
    }

    protected void transmitRealtimeTM(CCSDSPacket packet) {
        packet.fillChecksum();
        if (isLOS()) {
            losRecorder.record(packet);
        } else {
            tmLink.sendPacket(packet.toByteArray());
            if (frameLink != null) {
                frameLink.queuePacket(0, packet.toByteArray());
            }

        }
    }

    protected void transmitCfdp(CfdpPacket packet) {
        cfdpLink.sendPacket(packet.toByteArray());
    }

    protected void processCfdp(CfdpPacket packet) {
        if (packet.getHeader().isFileDirective()) {
            switch (((FileDirective) packet).getFileDirectiveCode()) {
            case EOF:
                // 1 in 2 chance that we did not receive the EOF packet
                if (Math.random() > 0.5) {
                    break;
                }

                log.info("EOF CFDP packet received, sending back ACK (EOF) packet");
                EofPacket p = (EofPacket) packet;

                CfdpHeader header = new CfdpHeader(
                        true,
                        true,
                        false,
                        false,
                        packet.getHeader().getEntityIdLength(),
                        packet.getHeader().getSequenceNumberLength(),
                        packet.getHeader().getSourceId(),
                        packet.getHeader().getDestinationId(),
                        packet.getHeader().getSequenceNumber());
                AckPacket EofAck = new AckPacket(
                        FileDirectiveCode.EOF,
                        FileDirectiveSubtypeCode.FinishedByWaypointOrOther,
                        ConditionCode.NoError,
                        TransactionStatus.Active,
                        header);
                transmitCfdp(EofAck);

                log.info("ACK (EOF) sent, delaying a bit and sending Finished packet");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                // checking the file completeness;
                missingSegments = cfdpDataFile.getMissingChunks();
                if (missingSegments.isEmpty()) {
                    log.info("File complete, sending back FinishedPacket");
                    header = new CfdpHeader(
                            true, // file directive
                            true, // towards sender
                            false, // not acknowledged
                            false, // no CRC
                            packet.getHeader().getEntityIdLength(),
                            packet.getHeader().getSequenceNumberLength(),
                            packet.getHeader().getSourceId(),
                            packet.getHeader().getDestinationId(),
                            packet.getHeader().getSequenceNumber());

                    FinishedPacket finished = new FinishedPacket(
                            ConditionCode.NoError,
                            true, // generated by end system
                            false, // data complete
                            FileStatus.SuccessfulRetention,
                            new ArrayList<FileStoreResponse>(),
                            null,
                            header);

                    transmitCfdp(finished);
                } else {
                    header = new CfdpHeader(
                            true, // file directive
                            true, // towards sender
                            false, // not acknowledged
                            false, // no CRC
                            packet.getHeader().getEntityIdLength(),
                            packet.getHeader().getSequenceNumberLength(),
                            packet.getHeader().getSourceId(),
                            packet.getHeader().getDestinationId(),
                            packet.getHeader().getSequenceNumber());

                    NakPacket nak = new NakPacket(
                            missingSegments.get(0).getSegmentStart(),
                            missingSegments.get(missingSegments.size() - 1).getSegmentEnd(),
                            missingSegments,
                            header);
                    transmitCfdp(nak);
                    log.info("File not complete, NAK sent back");

                }
                break;
            case Finished:
                log.info("Finished CFDP packet received");
                break;
            case ACK:
                log.info("ACK CFDP packet received");
                break;
            case Metadata:
                log.info("Metadata CFDP packet received");
                MetadataPacket metadata = (MetadataPacket) packet;
                long packetLength = metadata.getPacketLength();
                cfdpDataFile = new DataFile(packetLength);
                break;
            case NAK:
                log.info("NAK CFDP packet received");
                break;
            case Prompt:
                log.info("Prompt CFDP packet received");
                break;
            case KeepAlive:
                log.info("KeepAlive CFDP packet received");
                break;
            default:
                log.error("CFDP packet of unknown type received");
                break;
            }
        } else {
            FileDataPacket fdp = (FileDataPacket) packet;
            if (missingSegments == null || missingSegments.isEmpty()) {
                // we're not in "resending mode"
                // 1 in 5 chance to 'loose' the packet
                if (Math.random() > 0.8) {
                    log.info("'loosing' a FileDataPacket");
                } else {
                    cfdpDataFile.addSegment(new DataFileSegment(fdp.getOffset(), fdp.getData()));
                    log.info("file data received: " + new String(fdp.getData()).toString());
                }
            } else {
                // we're resending
                cfdpDataFile.addSegment(new DataFileSegment(fdp.getOffset(), fdp.getData()));
                missingSegments.remove(new SegmentRequest(fdp.getOffset(), fdp.getOffset() + fdp.getData().length));
                log.info("RESENT file data received: " + new String(fdp.getData()).toString());
                if (missingSegments.isEmpty()) {
                    CfdpHeader header = new CfdpHeader(
                            true, // file directive
                            true, // towards sender
                            false, // not acknowledged
                            false, // no CRC
                            packet.getHeader().getEntityIdLength(),
                            packet.getHeader().getSequenceNumberLength(),
                            packet.getHeader().getSourceId(),
                            packet.getHeader().getDestinationId(),
                            packet.getHeader().getSequenceNumber());

                    FinishedPacket finished = new FinishedPacket(
                            ConditionCode.NoError,
                            true, // generated by end system
                            false, // data complete
                            FileStatus.SuccessfulRetention,
                            new ArrayList<FileStoreResponse>(),
                            null,
                            header);

                    transmitCfdp(finished);

                }
            }
        }
    }

    protected void transmitTM2(byte[] packet) {
        if (!isLOS()) {
            tm2Link.sendPacket(packet);
            if (frameLink != null) {
                frameLink.queuePacket(1, encapsulate(packet));
            }
        }

    }

    // encapsulate packet
    byte[] encapsulate(byte[] p) {

        byte[] p1 = new byte[p.length + 4];
        System.arraycopy(p, 0, p1, 4, p.length);
        p1[0] = (byte) 0xFE;
        ByteArrayUtils.encodeShort(p1.length, p1, 2);
        return p1;
    }

    public void dumpLosDataFile(String filename) {
        // read data from los storage
        if (filename == null) {
            filename = losRecorder.getCurrentRecordingName();
            if (filename == null) {
                return;
            }
        }

        try (DataInputStream dataStream = new DataInputStream(losRecorder.getInputStream(filename))) {
            while (dataStream.available() > 0) {
                CCSDSPacket packet = readLosPacket(dataStream);
                if (packet != null) {
                    losLink.sendPacket(packet.toByteArray());
                    if (frameLink != null) {
                        frameLink.queuePacket(2, packet.toByteArray());
                    }
                }
            }

            // add packet notifying that the file has been downloaded entirely
            CCSDSPacket confirmationPacket = buildLosTransmittedRecordingPacket(filename);
            transmitRealtimeTM(confirmationPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static CCSDSPacket buildLosTransmittedRecordingPacket(String transmittedRecordName) {
        CCSDSPacket packet = new CCSDSPacket(0, 2, 10, false);
        packet.appendUserDataBuffer(transmittedRecordName.getBytes());
        packet.appendUserDataBuffer(new byte[1]);

        return packet;
    }

    public void deleteLosDataFile(String filename) {
        losRecorder.deleteDump(filename);
        // add packet notifying that the file has been deleted
        CCSDSPacket confirmationPacket = buildLosDeletedRecordingPacket(filename);
        transmitRealtimeTM(confirmationPacket);
    }

    private static CCSDSPacket buildLosDeletedRecordingPacket(String deletedRecordName) {
        CCSDSPacket packet = new CCSDSPacket(0, 2, 11, false);
        packet.appendUserDataBuffer(deletedRecordName.getBytes());
        packet.appendUserDataBuffer(new byte[1]);
        return packet;
    }

    protected CCSDSPacket ackPacket(CCSDSPacket commandPacket, int stage, int result) {
        CCSDSPacket ackPacket = new CCSDSPacket(0, commandPacket.getPacketType(), 2000, false);
        ackPacket.setApid(101);
        int batNum = commandPacket.getPacketId();

        ByteBuffer bb = ByteBuffer.allocate(10);

        bb.putInt(0, batNum);
        bb.putInt(4, commandPacket.getSeq());
        bb.put(8, (byte) stage);
        bb.put(9, (byte) result);

        ackPacket.appendUserDataBuffer(bb.array());

        return ackPacket;
    }

    private void sendFlightPacket() {
        CCSDSPacket flightpacket = new CCSDSPacket(60, 33);
        flightDataHandler.fillPacket(flightpacket);
        transmitRealtimeTM(flightpacket);
    }

    private void sendCfdp() {
        // byte[] filedata = { 'T', 'h', 'i', 's', ' ', 'i', 's', ' ', 'a', ' ', 't', 'e', 's', 't', '.' };
        // CfdpPacket cfdpFileData = new FileDataPacket(filedata, 0, FileDataPacket.createHeader(filedata));
        // transmitCfdp(cfdpFileData);
    }

    private void sendHkTm() {
        CCSDSPacket powerpacket = new CCSDSPacket(16, 1);
        powerDataHandler.fillPacket(powerpacket);
        transmitRealtimeTM(powerpacket);

        CCSDSPacket packet = new CCSDSPacket(9, 2);
        dhsHandler.fillPacket(packet);
        transmitRealtimeTM(packet);

        packet = new CCSDSPacket(36, 3);
        rcsHandler.fillPacket(packet);
        transmitRealtimeTM(packet);

        packet = new CCSDSPacket(6, 4);
        epslvpduHandler.fillPacket(packet);
        transmitRealtimeTM(packet);
    }

    /**
     * creates and sends a dummy packet with the following structure
     * <ul>
     * <li>size (2 bytes)</li>
     * <li>unix timestamp in millisec(8 bytes)</li>
     * <li>seq count(4 bytes)</li>
     * <li>uint32</li>
     * <li>64 bit float</li>
     * <li>checksum (2 bytes)</li>
     * </ul>
     */
    private void sendTm2() {
        int n = 28;
        ByteBuffer bb = ByteBuffer.allocate(n);
        bb.putShort((short) (n - 2));
        bb.putLong(System.currentTimeMillis());
        int seq = tm2SeqCount.getAndIncrement();
        bb.putInt(seq);
        bb.putInt(seq + 1000);
        bb.putDouble(Math.sin(seq / 10.0));
        bb.putShort((short) edwc2.compute(bb.array(), 0, n - 2));
        transmitTM2(bb.array());
    }

    /**
     * runs in the main TM thread, executes commands from the queue (if any)
     */
    private void executePendingCommands() {
        CCSDSPacket commandPacket;
        while ((commandPacket = pendingCommands.poll()) != null) {
            if (commandPacket.getPacketType() == 10) {
                log.info("BATT COMMAND: " + commandPacket.getPacketId());

                switch (commandPacket.getPacketId()) {
                case 1:
                    switchBatteryOn(commandPacket);
                    break;
                case 2:
                    switchBatteryOff(commandPacket);
                    break;
                case 5:
                    listRecordings(commandPacket);
                    break;
                case 6:
                    dumpRecording(commandPacket);
                    break;
                case 7:
                    deleteRecording(commandPacket);
                    break;
                default:
                    log.error("Invalid command packet id: {}", commandPacket.getPacketId());
                }
            } else {
                log.warn("Unknown command type " + commandPacket.getPacketType());
            }
        }
    }

    private void switchBatteryOn(CCSDSPacket commandPacket) {
        tmLink.ackPacketSend(ackPacket(commandPacket, 1, 0));
        commandPacket.setPacketId(1);
        int batNum = commandPacket.getUserDataBuffer().get(0);
        executor.schedule(() -> powerDataHandler.setBatteryOn(batNum), 500, TimeUnit.MILLISECONDS);
        tmLink.ackPacketSend(ackPacket(commandPacket, 2, 0));
    }

    private void switchBatteryOff(CCSDSPacket commandPacket) {
        tmLink.ackPacketSend(ackPacket(commandPacket, 1, 0));
        commandPacket.setPacketId(2);
        int batNum = commandPacket.getUserDataBuffer().get(0);
        executor.schedule(() -> powerDataHandler.setBatteryOff(batNum), 500, TimeUnit.MILLISECONDS);
        tmLink.ackPacketSend(ackPacket(commandPacket, 2, 0));
    }

    private void listRecordings(CCSDSPacket commandPacket) {
        tmLink.ackPacketSend(ackPacket(commandPacket, 1, 0));

        CCSDSPacket packet = new CCSDSPacket(0, 2, 9, false);
        String[] dumps = losRecorder.listRecordings();
        log.info("LOS dump count: {}", dumps.length);

        String joined = String.join(" ", dumps);
        packet.appendUserDataBuffer(joined.getBytes());
        packet.appendUserDataBuffer(new byte[1]); // terminate with \0

        transmitRealtimeTM(packet);
        tmLink.ackPacketSend(ackPacket(commandPacket, 2, 0));
    }

    private void dumpRecording(CCSDSPacket commandPacket) {
        tmLink.ackPacketSend(ackPacket(commandPacket, 1, 0));
        byte[] fileNameArray = commandPacket.getUserDataBuffer().array();
        int indexStartOfString = 16;
        int indexEndOfString = indexStartOfString;
        for (int i = indexStartOfString; i < fileNameArray.length; i++) {
            if (fileNameArray[i] == 0) {
                indexEndOfString = i;
                break;
            }
        }
        String fileName1 = new String(fileNameArray, indexStartOfString, indexEndOfString - indexStartOfString);
        log.info("Command DUMP_RECORDING for file {}", fileName1);
        dumpLosDataFile(fileName1);
        tmLink.ackPacketSend(ackPacket(commandPacket, 2, 0));
    }

    private void deleteRecording(CCSDSPacket commandPacket) {
        tmLink.ackPacketSend(ackPacket(commandPacket, 1, 0));
        byte[] fileNameArray = commandPacket.getUserDataBuffer().array();
        String fileName = new String(fileNameArray, 16, fileNameArray.length - 22);
        log.info("Command DELETE_RECORDING for file {}", fileName);
        deleteLosDataFile(fileName);
        tmLink.ackPacketSend(ackPacket(commandPacket, 2, 0));
    }

    public void setTmLink(TmTcLink tmLink) {
        this.tmLink = tmLink;
    }

    public void setTm2Link(TmTcLink tm2Link) {
        this.tm2Link = tm2Link;
    }

    public void setCfdpLink(UdpLink cfdpLink) {
        this.cfdpLink = cfdpLink;
    }

    public void processTc(CCSDSPacket tc) {
        tmLink.ackPacketSend(ackPacket(tc, 0, 0));
        try {
            pendingCommands.put(tc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected CCSDSPacket readLosPacket(DataInputStream dIn) {
        try {
            byte hdr[] = new byte[6];
            dIn.readFully(hdr);
            int remaining = ((hdr[4] & 0xFF) << 8) + (hdr[5] & 0xFF) + 1;
            if (remaining > maxLength - 6) {
                throw new IOException(
                        "Remaining packet length too big: " + remaining + " maximum allowed is " + (maxLength - 6));
            }
            byte[] b = new byte[6 + remaining];
            System.arraycopy(hdr, 0, b, 0, 6);
            dIn.readFully(b, 6, remaining);
            return new CCSDSPacket(ByteBuffer.wrap(b));
        } catch (Exception e) {
            log.error("Error reading LOS packet from file " + e.getMessage(), e);
        }
        return null;
    }

    public void setLosLink(TmTcLink losLink) {
        this.losLink = losLink;
    }

    public void setFrameLink(UdpFrameLink aosLink) {
        this.frameLink = aosLink;
    }

    @Override
    protected void doStart() {
        executor = new ScheduledThreadPoolExecutor(1);
        executor.scheduleAtFixedRate(() -> sendFlightPacket(), 0, 200, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> sendHkTm(), 0, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> sendTm2(), 0, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> sendCfdp(), 0, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> executePendingCommands(), 0, 200, TimeUnit.MILLISECONDS);

        notifyStarted();
    }

    @Override
    protected void doStop() {
        executor.shutdownNow();
        notifyStopped();
    }
}