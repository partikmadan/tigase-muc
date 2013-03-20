/*
 * Ghostbuster.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package tigase.muc;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import tigase.muc.modules.PresenceModule;
import tigase.server.Packet;
import tigase.server.ReceiverTimeoutHandler;
import tigase.util.TigaseStringprepException;
import tigase.xml.Element;
import tigase.xmpp.JID;

//~--- JDK imports ------------------------------------------------------------

/**
 * @author bmalkow
 * 
 */
public class Ghostbuster {
	private static class JIDDomain {
		private String cacheKey;
		private final String domain;
		private String lastUsedRoomName;
		private final JID source;

		/**
		 * 
		 * 
		 * @param source
		 * @param domain
		 */
		public JIDDomain(JID source, String domain) {
			this.source = source;
			this.domain = domain;
			cacheKey = ((this.source == null) ? "-" : this.source.toString()) + ":"
					+ ((this.domain == null) ? "-" : this.domain.toString());
		}

		/**
		 * Method description
		 * 
		 * 
		 * @param obj
		 * 
		 * @return
		 */
		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (!(obj instanceof JIDDomain)) {
				return false;
			}

			return cacheKey.equals(((JIDDomain) obj).cacheKey);
		}

		/**
		 * Method description
		 * 
		 * 
		 * @return
		 */
		@Override
		public int hashCode() {
			return cacheKey.hashCode();
		}

		/**
		 * Method description
		 * 
		 * 
		 * @return
		 */
		@Override
		public String toString() {
			return cacheKey;
		}
	}

	private static final Set<String> intReasons = new HashSet<String>() {
		private static final long serialVersionUID = 1L;
		{
			add("gone");
			add("item-not-found");
			add("recipient-unavailable");
			add("redirect");
			add("remote-server-not-found");
			add("remote-server-timeout");
		}
	};

	/** Field description */
	public static final Set<String> R = Collections.unmodifiableSet(intReasons);

	private long idCounter;
	private final Map<JIDDomain, Long> lastActivity = new ConcurrentHashMap<JIDDomain, Long>();
	/** Field description */
	protected Logger log = Logger.getLogger(this.getClass().getName());
	private final MUCComponent mucComponent;
	private final ReceiverTimeoutHandler pingHandler;

	private PresenceModule presenceModule;

	/**
	 * @param mucComponent
	 */
	public Ghostbuster(MUCComponent mucComponent) {
		this.mucComponent = mucComponent;
		this.pingHandler = new ReceiverTimeoutHandler() {
			@Override
			public void responseReceived(Packet data, Packet response) {
				try {
					onPingReceived(response);
				} catch (Exception e) {
					if (log.isLoggable(Level.WARNING)) {
						log.log(Level.WARNING, "Problem on handling ping response", e);
					}
				}
			}

			@Override
			public void timeOutExpired(Packet data) {
				try {
					if (log.isLoggable(Level.FINEST)) {
						log.finest("Received ping timeout for ping " + data.getElement().getAttributeStaticStr("id"));
					}
					onPingTimeout(data.getStanzaFrom().getDomain(), data.getStanzaTo());
				} catch (Exception e) {
					if (log.isLoggable(Level.WARNING)) {
						log.log(Level.WARNING, "Problem on handling ping timeout", e);
					}
				}
			}
		};
	}

	/**
	 * @param packet
	 */
	private String checkError(final Packet packet) {
		final String type = packet.getElement().getAttributeStaticStr(Packet.TYPE_ATT);

		if ((type == null) || !type.equals("error")) {
			return null;
		}

		final Element errorElement = packet.getElement().getChild("error");

		if (errorElement == null) {
			return null;
		}
		for (Element reason : errorElement.getChildren()) {
			if ((reason.getXMLNS() == null) || !reason.getXMLNS().equals("urn:ietf:params:xml:ns:xmpp-stanzas")) {
				continue;
			}
			if (Ghostbuster.R.contains(reason.getName())) {
				return reason.getName();
			}
		}

		return null;
	}

	/**
	 * Method description
	 * 
	 * 
	 * @return
	 */
	public PresenceModule getPresenceModule() {
		return presenceModule;
	}

	/**
	 * 
	 * @param response
	 * @throws TigaseStringprepException
	 */
	protected void onPingReceived(final Packet response) throws TigaseStringprepException {
		final JIDDomain k = new JIDDomain(response.getStanzaFrom(), response.getStanzaTo().getDomain());

		if (lastActivity.containsKey(k)) {
			final String errorCause = checkError(response);

			if (errorCause != null) {
				if (log.isLoggable(Level.FINEST)) {
					log.finest("Received error response for ping " + response.getElement().getAttributeStaticStr(Packet.ID_ATT)
							+ "(" + checkError(response) + ") of" + k);
				}
				processError(k);
			} else {
				if (log.isLoggable(Level.FINEST)) {
					log.finest("Update last activity for " + k);
				}
				lastActivity.put(k, System.currentTimeMillis());
			}
		}
	}

	/**
	 * @param domain
	 * @param jid
	 */
	protected void onPingTimeout(String domain, final JID jid) {
		try {
			processError(new JIDDomain(jid, domain));
		} catch (TigaseStringprepException e) {
			if (log.isLoggable(Level.WARNING)) {
				log.log(Level.WARNING, "Invalid jid?", e);
			}
		}
	}

	/**
	 * @throws TigaseStringprepException
	 * 
	 */
	public void ping() throws TigaseStringprepException {
		if (log.isLoggable(Level.FINE)) {
			log.log(Level.FINE, "Pinging up to 1000 known JIDs with 1h of inactivity");
		}

		int c = 0;
		final long now = System.currentTimeMillis();
		final long border = now - 1000 * 60 * 60;
		Iterator<Entry<JIDDomain, Long>> it = lastActivity.entrySet().iterator();

		while (it.hasNext() && (c < 1000)) {
			Entry<JIDDomain, Long> entry = it.next();

			if (entry.getValue() < border) {
				++c;
				ping(entry.getKey().lastUsedRoomName, entry.getKey().domain, entry.getKey().source);
			}
		}
	}

	private void ping(String roomName, String fromDomain, JID jid) throws TigaseStringprepException {
		final String id = "png-" + (++idCounter);

		if (log.isLoggable(Level.FINER)) {
			log.log(Level.FINER, "Pinging " + jid + ". id=" + id);
		}

		final String from = (roomName != null ? (roomName + "@") : "") + fromDomain;

		Element ping = new Element("iq", new String[] { "type", "id", "from", "to" }, new String[] { "get", id, from,
				jid.toString() });

		ping.addChild(new Element("ping", new String[] { "xmlns" }, new String[] { "urn:xmpp:ping" }));

		Packet packet = Packet.packetInstance(ping);

		mucComponent.addOutPacket(packet, pingHandler, 1, TimeUnit.MINUTES);

		if (log.isLoggable(Level.FINER))
			log.log(Level.FINER, "Pinged " + jid);
	}

	/**
	 * @param packet
	 * @throws TigaseStringprepException
	 */
	private void processError(JIDDomain k) throws TigaseStringprepException {
		if ((presenceModule == null) || (mucComponent.getMucRepository() == null)) {
			return;
		}
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Forced removal last activity of " + k);
		}
		this.lastActivity.remove(k);
		for (Room r : mucComponent.getMucRepository().getActiveRooms().values()) {
			if (r.getRoomJID().getDomain().equals(k.domain) && r.isOccupantInRoom(k.source)) {
				presenceModule.doQuit(r, k.source);
			}
		}
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param presenceModule
	 */
	public void setPresenceModule(PresenceModule presenceModule) {
		this.presenceModule = presenceModule;
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param packet
	 * 
	 * @throws TigaseStringprepException
	 */
	public void update(Packet packet) throws TigaseStringprepException {
		if ((packet.getStanzaFrom() == null) || (packet.getStanzaFrom().getResource() == null)) {
			return;
		}

		final JIDDomain k = new JIDDomain(packet.getStanzaFrom(), packet.getStanzaTo().getDomain());

		k.lastUsedRoomName = packet.getStanzaTo().getLocalpart();

		final String type = packet.getElement().getAttributeStaticStr(Packet.TYPE_ATT);

		if (checkError(packet) != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Received presence error: " + packet.getElement().toString());
			}
			processError(k);
		} else if ("presence".equals(packet.getElemName()) && (type != null) && type.equals("unavailable")) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Removal last activity of " + k);
			}
			this.lastActivity.remove(k);
		} else if (!lastActivity.containsKey(k) && "presence".equals(packet.getElemName())
				&& ((type == null) || !type.equals("error"))) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Creation last activity entry for " + k);
			}
			lastActivity.put(k, System.currentTimeMillis());

			return;
		}
		if (lastActivity.containsKey(k)) {
			if (log.isLoggable(Level.FINEST)) {
				log.finest("Update last activity for " + k);
			}
			lastActivity.put(k, System.currentTimeMillis());
		}
	}
}