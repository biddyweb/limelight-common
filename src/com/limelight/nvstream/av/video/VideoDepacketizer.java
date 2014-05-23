package com.limelight.nvstream.av.video;

import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.ByteBufferDescriptor;
import com.limelight.nvstream.av.DecodeUnit;
import com.limelight.nvstream.av.RtpPacket;
import com.limelight.nvstream.av.ConnectionStatusListener;

public class VideoDepacketizer {
	
	// Current frame state
	private LinkedList<ByteBufferDescriptor> avcFrameDataChain = null;
	private int avcFrameDataLength = 0;
	private int currentlyDecoding = DecodeUnit.TYPE_UNKNOWN;
	
	// Sequencing state
	private int lastPacketInStream = 0;
	private boolean waitingForNextSuccessfulFrame;
	
	// Cached objects
	private ByteBufferDescriptor cachedDesc = new ByteBufferDescriptor(null, 0, 0);
	
	private ConnectionStatusListener controlListener;
	private VideoDecoderRenderer directSubmitDr;
	
	private static final int DU_LIMIT = 15;
	private LinkedBlockingQueue<DecodeUnit> decodedUnits = new LinkedBlockingQueue<DecodeUnit>(DU_LIMIT);
	
	public VideoDepacketizer(VideoDecoderRenderer directSubmitDr, ConnectionStatusListener controlListener)
	{
		this.directSubmitDr = directSubmitDr;
		this.controlListener = controlListener;
	}
	
	private void clearAvcFrameState()
	{
		avcFrameDataChain = null;
		avcFrameDataLength = 0;
	}
	
	private void reassembleAvcFrame(int frameNumber)
	{
		// This is the start of a new frame
		if (avcFrameDataChain != null && avcFrameDataLength != 0) {
			int flags = 0;
			
			ByteBufferDescriptor firstBuffer = avcFrameDataChain.getFirst();
			
			if (NAL.getSpecialSequenceDescriptor(firstBuffer, cachedDesc) && NAL.isAvcFrameStart(cachedDesc)) {
				switch (cachedDesc.data[cachedDesc.offset+cachedDesc.length]) {
				case 0x67:
				case 0x68:
					flags |= DecodeUnit.DU_FLAG_CODEC_CONFIG;
					break;
					
				case 0x65:
					flags |= DecodeUnit.DU_FLAG_SYNC_FRAME;
					break;
				}
			}
			
			// Construct the H264 decode unit
			DecodeUnit du = new DecodeUnit(DecodeUnit.TYPE_H264, avcFrameDataChain, avcFrameDataLength, flags, frameNumber);
			if (directSubmitDr != null) {
				// Submit directly to the decoder
				directSubmitDr.submitDecodeUnit(du);
			}
			else if (!decodedUnits.offer(du)) {
				LimeLog.warning("Video decoder is too slow! Forced to drop decode units");
				
				// Invalidate all frames from the start of the DU queue
				controlListener.connectionSinkTooSlow(decodedUnits.remove().getFrameNumber(), frameNumber);
				
				// Remove existing frames
				decodedUnits.clear();
				
				// Add this frame
				decodedUnits.add(du);
			}
			
			controlListener.connectionReceivedFrame(frameNumber);

			// Clear old state
			clearAvcFrameState();
		}
	}
	
	public void addInputDataSlow(VideoPacket packet, ByteBufferDescriptor location)
	{
		while (location.length != 0)
		{
			// Remember the start of the NAL data in this packet
			int start = location.offset;

			// Check for a special sequence
			if (NAL.getSpecialSequenceDescriptor(location, cachedDesc))
			{
				if (NAL.isAvcStartSequence(cachedDesc))
				{
					// We're decoding H264 now
					currentlyDecoding = DecodeUnit.TYPE_H264;

					// Check if it's the end of the last frame
					if (NAL.isAvcFrameStart(cachedDesc))
					{
						// Reassemble any pending AVC NAL
						reassembleAvcFrame(packet.getFrameIndex());

						// Setup state for the new NAL
						avcFrameDataChain = new LinkedList<ByteBufferDescriptor>();
						avcFrameDataLength = 0;
					}

					// Skip the start sequence
					location.length -= cachedDesc.length;
					location.offset += cachedDesc.length;
				}
				else
				{
					// Check if this is padding after a full AVC frame
					if (currentlyDecoding == DecodeUnit.TYPE_H264 &&
						NAL.isPadding(cachedDesc)) {
						// The decode unit is complete
						reassembleAvcFrame(packet.getFrameIndex());
					}

					// Not decoding AVC
					currentlyDecoding = DecodeUnit.TYPE_UNKNOWN;

					// Just skip this byte
					location.length--;
					location.offset++;
				}
			}

			// Move to the next special sequence
			while (location.length != 0)
			{
				// Catch the easy case first where byte 0 != 0x00
				if (location.data[location.offset] == 0x00)
				{
					// Check if this should end the current NAL
					if (NAL.getSpecialSequenceDescriptor(location, cachedDesc))
					{
						// Only stop if we're decoding something or this
						// isn't padding
						if (currentlyDecoding != DecodeUnit.TYPE_UNKNOWN ||
							!NAL.isPadding(cachedDesc))
						{
							break;
						}
					}
				}

				// This byte is part of the NAL data
				location.offset++;
				location.length--;
			}

			if (currentlyDecoding == DecodeUnit.TYPE_H264 && avcFrameDataChain != null)
			{
				ByteBufferDescriptor data = new ByteBufferDescriptor(location.data, start, location.offset-start);

				// Add a buffer descriptor describing the NAL data in this packet
				avcFrameDataChain.add(data);
				avcFrameDataLength += location.offset-start;
			}
		}
	}
	
	public void addInputDataFast(VideoPacket packet, ByteBufferDescriptor location, boolean firstPacket)
	{
		if (firstPacket) {
			// Setup state for the new frame
			avcFrameDataChain = new LinkedList<ByteBufferDescriptor>();
			avcFrameDataLength = 0;
		}
		
		if (avcFrameDataChain != null) {
			// Add the payload data to the chain
			avcFrameDataChain.add(location);
			avcFrameDataLength += location.length;
		}
	}
	
	public void addInputData(VideoPacket packet)
	{	
		ByteBufferDescriptor location = packet.getNewPayloadDescriptor();
		
		int streamPacketIndex = packet.getPacketIndex();
		
		// Check that this is the next frame
		boolean firstPacket = (packet.getFlags() & VideoPacket.FLAG_SOF) != 0;
		if (streamPacketIndex != (int)(lastPacketInStream + 1)) {
			LimeLog.warning("Packet loss from "+(lastPacketInStream + 1)+" to "+streamPacketIndex);
			
			// Packets were lost so report this to the server
			controlListener.connectionLostPackets(lastPacketInStream, streamPacketIndex);
			
			// Start waiting for the next frame
			waitingForNextSuccessfulFrame = true;
			clearAvcFrameState();
			lastPacketInStream = streamPacketIndex;
			return;
		}
		else {
			lastPacketInStream = streamPacketIndex;
		}
		
		// Remove extra padding
		location.length = packet.getPayloadLength();
		
		if (firstPacket)
		{
			if (NAL.getSpecialSequenceDescriptor(location, cachedDesc) && NAL.isAvcFrameStart(cachedDesc)
				&& cachedDesc.data[cachedDesc.offset+cachedDesc.length] == 0x67)
			{
				// SPS and PPS prefix is padded between NALs, so we must decode it with the slow path
				clearAvcFrameState();
				addInputDataSlow(packet, location);
				return;
			}
		}

		addInputDataFast(packet, location, firstPacket);
		
		if ((packet.getFlags() & VideoPacket.FLAG_EOF) != 0) {
	        reassembleAvcFrame(packet.getFrameIndex());
			
			if (waitingForNextSuccessfulFrame) {
				// Arguments are unused currently
				controlListener.connectionDetectedFrameLoss(-1, -1);
				waitingForNextSuccessfulFrame = false;
			}
		}
	}
	
	public void addInputData(RtpPacket packet)
	{
		ByteBufferDescriptor rtpPayload = packet.getNewPayloadDescriptor();
		addInputData(new VideoPacket(rtpPayload));
	}
	
	public DecodeUnit getNextDecodeUnit() throws InterruptedException
	{
		return decodedUnits.take();
	}
}
