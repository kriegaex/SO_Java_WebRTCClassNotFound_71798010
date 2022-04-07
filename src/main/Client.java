package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.json.simple.JSONObject;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;

/*
 * 
 * This implements a weird idea to combine the web socket connection and the RTC peer connection
 * into a single class.
 * Why? the two parts often work together.
 */

@ServerEndpoint("/")
public class Client implements PeerConnectionObserver {

	private final RTCPeerConnection peerConnection;
	
	private String sessionId;
	private Session session;
	
	public Client() {
		
		RTCConfiguration rtcConfiguration = new RTCConfiguration();

		RTCIceServer stunServer = new RTCIceServer();
		stunServer.urls.add("stun:stun.l.google.com:19302");
		rtcConfiguration.iceServers.add(stunServer);

		PeerConnectionFactory peerConnectionFactory = new PeerConnectionFactory();
		
		System.out.printf("creating peer connection\n");
		
		peerConnection = peerConnectionFactory.createPeerConnection(rtcConfiguration, this);
		
	}
	
	public RTCPeerConnection getPeerConnection() {
		return peerConnection;
	}

	@OnOpen
    public void onOpen(Session session) {
		
		this.session = session;
		this.sessionId =  session.getId();
		
		System.out.printf("onOpen:: %s\n", sessionId);        

    	ConnectionManager.put(sessionId, this);
        
        
        final String payload = String.format("{\"message\":\"greeting\",\"sessionid\":\"%s\"}", sessionId);
    	sendMessage(payload);
	
	}
    @OnClose
    public void onClose(Session session) {
    	
		this.session = session;
		this.sessionId =  session.getId();

		ConnectionManager.remove(this);
		
		System.out.printf("onClose:: %s\n", sessionId);        
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {

		this.session = session;
		this.sessionId =  session.getId();

		final MessageTask messageTask = new MessageTask(this, message);
		messageTask.go();
		
    }
    
    public void sendMessage(String payload) {
    	
        try {
        	
        	session.getBasicRemote().sendText(payload);
        	
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    @OnError
    public void onError(Throwable t) {
    	System.out.printf("onError:: %s\n", t.getMessage());
    }

    public Session getSession() {
    	return this.session;
    }
    
    public String getSessionId() {
    	return this.sessionId;
    }
    
    // PeerConnectionObserver
	public void setRemoteDescrption(JSONObject sdp) { 
		
		//final JSONParser jsonParser = new JSONParser();
		//final JSONObject jsonObject = (JSONObject)jsonParser.parse(jsep);
		
		final String sdpType = (String)sdp.get("type");
		final RTCSdpType remoteSdpType = (sdpType.equalsIgnoreCase("offer")) ? RTCSdpType.OFFER : RTCSdpType.ANSWER;
		final RTCSessionDescription remoteDescription = new RTCSessionDescription(remoteSdpType, (String)sdp.get("sdp"));

		if (remoteSdpType == RTCSdpType.OFFER) {
			System.out.printf("sdp is an offer\n");
		} else {
			System.out.printf("sdp is an answer\n");
		}
		
		final SetSessionDescriptionObserver setLocalDescriptionObserver =
			new SetSessionDescriptionObserver() {

				@Override
				public void onSuccess() {
					System.out.printf("Local Description Set\n");
					
				}

				@Override
				public void onFailure(String error) {
					System.out.printf("Error setting local description\n");
					
				} 
			};

		final CreateSessionDescriptionObserver createSessionDescriptionObserver = 
			new CreateSessionDescriptionObserver() {

				@Override
				public void onSuccess(RTCSessionDescription description) {
					
					peerConnection.setLocalDescription(description, setLocalDescriptionObserver);
					
					
					final String payload = 
						String.format(
							"{\"message\":\"answer\",\"sdp\":{\"type\":\"%s\",\"sdp\":\"%s\"}}",
							description.sdpType.toString().toLowerCase(), 
							description.sdp.toString().replace("\r\n", "\\r\\n"));
					
					sendMessage(payload);
					
					System.out.printf("Answer created\n");
					
				}

				@Override
				public void onFailure(String error) {
					System.out.printf("Could not create Answer\n");
					
				}
		
			};		
				
		final SetSessionDescriptionObserver setRemoteDescriptionObserver =
			new SetSessionDescriptionObserver() {

				@Override
				public void onSuccess() {

					System.out.printf("Remote Description Set\n");
					
					if (remoteSdpType == RTCSdpType.OFFER) {
						System.out.printf("Creating Answer\n");
						
						final RTCAnswerOptions rtcAnswerOptions = new RTCAnswerOptions();
						rtcAnswerOptions.voiceActivityDetection = false;
						
						peerConnection.createAnswer(rtcAnswerOptions, createSessionDescriptionObserver);

					} else {
						System.out.printf("Remote %s set\n", sdpType);

					}
				}

				@Override
				public void onFailure(String error) {
					
					System.out.printf("Could not set Remote Description %s\n", sdpType);
					
				} 
			};
		
		peerConnection.setRemoteDescription(remoteDescription, setRemoteDescriptionObserver);
		
		System.out.printf("Receivers: %d\n", peerConnection.getReceivers().length);
		System.out.printf("Senders: %d\n", peerConnection.getSenders().length);
		System.out.printf("Transceivers: %d\n", peerConnection.getTransceivers().length);

	}
	
	@Override
	public void onIceCandidate(RTCIceCandidate iceCandidate) {
		
		if (iceCandidate == null) return;
		
		final String candidate = String.format(
				"{\"sdpMid\":\"%s\", \"sdpMLineIndex\":%d, \"candidate\":\"%s\"}",
				iceCandidate.sdpMid,
				iceCandidate.sdpMLineIndex,
				iceCandidate.sdp
			);
		
		
		final String payload = String.format("{\"message\":\"icecandidate\",\"candidate\":%s}", candidate);
		
		sendMessage(payload);
		
	}
	
	public void addIceCandidate(JSONObject candidate) {
		
		final String sdp = candidate.get("candidate").toString();
		final String sdpMid = candidate.get("sdpMid").toString();
		final int sdpMLineIndex = Integer.parseInt(candidate.get("sdpMLineIndex").toString());
		
		RTCIceCandidate rtcCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, sdp);
		
		peerConnection.addIceCandidate(rtcCandidate);
	}
	
	@Override
	public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
		
		final MediaStreamTrack track = receiver.getTrack();
		System.out.printf("onAddTrack %s\n", track.getKind());
		
	}
	
	public void mirror() {
		
		connect(this);
	}

	public void connect(Client clientToConnect) {
		
		final RTCPeerConnection connectionToConnect = clientToConnect.peerConnection; 
		
		final int receivers = connectionToConnect.getReceivers().length;
		
		if (receivers == 0) return;
		
		for (int index = 0; index < receivers; index++) {
			
			final RTCRtpReceiver receiver = connectionToConnect.getReceivers()[index];
			if (receiver != null) {
				
				final MediaStreamTrack track = receiver.getTrack();
				if (track != null) {
					
					System.out.printf("Adding track: %s\n", track.getKind());
					
					List<String> streamIds = new ArrayList<String>();
					streamIds.add(receiver.getTrack().getId());
					
					@SuppressWarnings("unused")
					RTCRtpSender sender = this.peerConnection.addTrack(track, streamIds);
				}
			}
		}
		
        final String payload = String.format("{\"message\":\"negotiate\"}");
    	sendMessage(payload);

		
	}

}
