/**
 * This Source Code Form is subject to the terms of the MIT License. If a copy
 * of the MPL was not distributed with this file, You can obtain one at 
 * https://opensource.org/licenses/MIT.
 */
package org.openmrs.module.pacsquery;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.json.JSONWriter;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;

import javax.json.Json;
import javax.json.stream.JsonGenerator;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class PacsQueryService {
	
	private static final String AE_TITLE = "PACSQUERY";
	
	private static final String DEVICE_NAME = "findscu";
	
	String aetitle;
	
	String host;
	
	int port;
	
	int[] tags;
	
	Writer respWriter;
	
	public PacsQueryService() {
		AdministrationService administrationService = Context.getAdministrationService();
		
		String pacsConfig = administrationService.getGlobalProperty("pacsquery.pacsConfig");
		// DCM4CHEE@localhost:11112
		String[] parts = pacsConfig.split("@");
		this.aetitle = parts[0];
		this.host = parts[1];
		parts = this.host.split(":");
		this.host = parts[0];
		this.port = Integer.parseInt(parts[1]);
		
		String retrieveTags = administrationService.getGlobalProperty("pacsquery.retrieveTags");
		//00000000,34323431
		String[] tagsString = retrieveTags.split(",");
		this.tags = new int[tagsString.length];
		for (int i = 0; i < tagsString.length; i++) {
			tags[i] = (int) Long.parseLong(tagsString[i], 16);
		}
	}
	
	public PacsQueryService(Writer respWriter) {
		this();
		this.setWriter(respWriter);
	}
	
	public void setWriter(Writer respWriter) {
		this.respWriter = respWriter;
	}
	
	// roughly stolen from FindSCU.java
	public void query(String patientId, String date) throws Exception {
		verifyParameters(patientId, date);
		// Create a device for query
		Device device = new Device("findscu");
		// Create remote connection
		Connection remote = new Connection("pacs", this.host, this.port);
		// Create a new connection
		Connection conn = getAEConnection();
		remote.setTlsProtocols(conn.getTlsProtocols());
		remote.setTlsCipherSuites(conn.getTlsCipherSuites());
		
		// Create Application Entity
		ApplicationEntity ae = new ApplicationEntity(AE_TITLE);
		ae.addConnection(conn);
		device.addConnection(conn);
		device.addApplicationEntity(ae);
		
		// Create Association Request
		AAssociateRQ rq = new AAssociateRQ();
		rq.setCalledAET(this.aetitle);
		// pulled from CLIUtils.java
		String[] IVR_LE_FIRST = { UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian };
		rq.addPresentationContext(new PresentationContext(1, UID.StudyRootQueryRetrieveInformationModelFind, IVR_LE_FIRST));
		
		// Create Attributes
		//System.out.println("Creating query attributes");
		Attributes attr = new Attributes();
		// Add study level
		attr.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
		// request params
		for (int tag : this.tags) {
			attr.setNull(tag, ElementDictionary.vrOf(tag, null));
		}
		// Match query params
		if (!patientId.isEmpty()) {
			attr.setString(0x00100020, VR.LO, patientId);
		}
		if (!date.isEmpty()) {
			attr.setString(0x00080020, VR.DA, date); // date-date
		}
		
		// Create Executor Service
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		device.setExecutor(executorService);
		device.setScheduledExecutor(scheduledExecutorService);
		
		// Run the query and write the result
		Association association = null;
		JsonGenerator jsonGenerator = null;
		try {
			association = ae.connect(remote, rq);
			// Build a JSON Generator and Writer
			jsonGenerator = Json.createGenerator(this.respWriter);
			jsonGenerator.writeStartArray();
			JSONWriter jsonWriter = new JSONWriter(jsonGenerator);
			DimseRSPHandler rspHandler = new PacsQueryDimseRSPHandler(association.nextMessageID(), jsonWriter);
			association.cfind(UID.StudyRootQueryRetrieveInformationModelFind, Priority.NORMAL, attr, null, rspHandler);
		}
		catch (Exception e) {
			throw new Exception("Query failed: " + e.getMessage());
		}
		finally {
			if (association != null && association.isReadyForDataTransfer()) {
				association.waitForOutstandingRSP();
				association.release();
			}
			executorService.shutdown();
			scheduledExecutorService.shutdown();
			
			if (jsonGenerator != null) {
				try {
					jsonGenerator.writeEnd();
				}
				catch (Exception e) {
					System.out.println(e.getMessage());
				}
				jsonGenerator.close();
			}
		}
	}
	
	public List<Map<String, Object>> dicomQuery(String patientId, String date) throws Exception {
		verifyParameters(patientId, date);
		Device device = new Device(DEVICE_NAME);
		Connection remoteConnection = new Connection("pacs", this.host, this.port);
		Connection aeConnection = getAEConnection();
		remoteConnection.setTlsProtocols(aeConnection.getTlsProtocols());
		remoteConnection.setTlsCipherSuites(aeConnection.getTlsCipherSuites());
		
		// Create Application Entity
		ApplicationEntity ae = new ApplicationEntity(AE_TITLE);
		ae.addConnection(aeConnection);
		device.addConnection(aeConnection);
		device.addApplicationEntity(ae);
		
		// Create Association Request
		AAssociateRQ request = new AAssociateRQ();
		request.setCalledAET(this.aetitle);
		// pulled from CLIUtils.java
		String[] IVR_LE_FIRST = { UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian };
		request.addPresentationContext(new PresentationContext(1, UID.StudyRootQueryRetrieveInformationModelFind,
		        IVR_LE_FIRST));
		
		// Create Attributes
		Attributes attr = new Attributes();
		// Add study level
		attr.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
		// request params
		for (int tag : this.tags) {
			attr.setNull(tag, ElementDictionary.vrOf(tag, null));
		}
		// Match query params
		if (!patientId.isEmpty()) {
			attr.setString(0x00100020, VR.LO, patientId);
		}
		if (!date.isEmpty()) {
			attr.setString(0x00080020, VR.DA, date); // date-date
		}
		
		// Create Executor Service
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		device.setExecutor(executorService);
		device.setScheduledExecutor(scheduledExecutorService);
		
		// Run the query and write the result
		Association association = null;
		try {
			association = ae.connect(remoteConnection, request);
			PatientStudyDimseRSPHandler rspHandler = new PatientStudyDimseRSPHandler(association.nextMessageID());
			association.cfind(UID.StudyRootQueryRetrieveInformationModelFind, Priority.NORMAL, attr, null, rspHandler);
			return rspHandler.getStudies();
		}
		catch (Exception e) {
			throw new Exception("Query failed: " + e.getMessage());
		}
		finally {
			if (association != null && association.isReadyForDataTransfer()) {
				association.waitForOutstandingRSP();
				association.release();
			}
			executorService.shutdown();
			scheduledExecutorService.shutdown();
		}
	}
	
	private void verifyParameters(String patientId, String date) throws Exception {
		if (patientId.isEmpty() && date.isEmpty()) {
			throw new Exception("At least one of patientId, date required.");
		}
		if (!patientId.isEmpty() && !patientId.matches("^[A-Za-z]{0,3}[0-9]+$")) {
			throw new Exception("patientId must be numeric");
		}
		if (!date.isEmpty() && !date.matches("^[0-9]{4}[0-1][0-9][0-3][0-9]$")) {
			throw new Exception("date must be of format YYYYMMDD");
		}
	}
	
	private Connection getAEConnection() {
		Connection conn = new Connection();
		// Set connection properties
		conn.setReceivePDULength(Connection.DEF_MAX_PDU_LENGTH);
		conn.setSendPDULength(Connection.DEF_MAX_PDU_LENGTH);
		conn.setMaxOpsInvoked(0);
		conn.setMaxOpsPerformed(0);
		conn.setPackPDV(true);
		conn.setConnectTimeout(10000); // 10 sec
		conn.setRequestTimeout(10000); // 10 sec
		conn.setAcceptTimeout(0);
		conn.setReleaseTimeout(0);
		conn.setResponseTimeout(0);
		conn.setRetrieveTimeout(0);
		conn.setIdleTimeout(0);
		conn.setSocketCloseDelay(Connection.DEF_SOCKETDELAY);
		conn.setSendBufferSize(0);
		conn.setReceiveBufferSize(0);
		conn.setTcpNoDelay(true);
		return conn;
	}
}
