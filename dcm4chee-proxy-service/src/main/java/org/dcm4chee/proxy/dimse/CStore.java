/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.proxy.dimse;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.dcm4che.conf.api.ApplicationEntityCache;
import org.dcm4che.conf.api.ConfigurationException;
import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.data.VR;
import org.dcm4che.emf.MultiframeExtractor;
import org.dcm4che.io.DicomEncodingOptions;
import org.dcm4che.io.DicomInputStream;
import org.dcm4che.io.DicomInputStream.IncludeBulkData;
import org.dcm4che.io.DicomOutputStream;
import org.dcm4che.net.Association;
import org.dcm4che.net.Commands;
import org.dcm4che.net.DataWriter;
import org.dcm4che.net.DataWriterAdapter;
import org.dcm4che.net.Dimse;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.InputStreamDataWriter;
import org.dcm4che.net.PDVInputStream;
import org.dcm4che.net.Status;
import org.dcm4che.net.TransferCapability.Role;
import org.dcm4che.net.pdu.AAssociateRQ;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.net.service.BasicCStoreSCP;
import org.dcm4che.net.service.DicomServiceException;
import org.dcm4chee.proxy.common.AuditDirectory;
import org.dcm4chee.proxy.common.CMoveInfoObject;
import org.dcm4chee.proxy.common.RetryObject;
import org.dcm4chee.proxy.conf.ForwardOption;
import org.dcm4chee.proxy.conf.ForwardRule;
import org.dcm4chee.proxy.conf.ProxyAEExtension;
import org.dcm4chee.proxy.utils.AttributeCoercionUtils;
import org.dcm4chee.proxy.utils.ForwardConnectionUtils;
import org.dcm4chee.proxy.utils.ForwardRuleUtils;
import org.dcm4chee.proxy.utils.InfoFileUtils;
import org.dcm4chee.proxy.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Michael Backhaus <michael.backaus@agfa.com>
 */
public class CStore extends BasicCStoreSCP {

    protected static final Logger LOG = LoggerFactory.getLogger(CStore.class);

    private ApplicationEntityCache aeCache;

    public CStore(ApplicationEntityCache aeCache, String... sopClasses) {
        super(sopClasses);
        this.aeCache = aeCache;
    }

    @Override
    public void onDimseRQ(Association asAccepted, PresentationContext pc, Dimse dimse, Attributes rq,
            PDVInputStream data) throws IOException {
        if (dimse != Dimse.C_STORE_RQ)
            throw new DicomServiceException(Status.UnrecognizedOperation);

        ProxyAEExtension proxyAEE = asAccepted.getApplicationEntity().getAEExtension(ProxyAEExtension.class);
        Object forwardAssociationProperty = asAccepted.getProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        if (spoolRequest(asAccepted, dimse, rq, proxyAEE, forwardAssociationProperty))
            spool(proxyAEE, asAccepted, pc, dimse, rq, data, null);
        else {
            try {
                forward(proxyAEE, asAccepted, (Association) forwardAssociationProperty, pc, rq, 
                        new InputStreamDataWriter(data), -1, null, null, null);
            } catch (Exception e) {
                LOG.error(asAccepted + ": error forwarding C-STORE-RQ: " + e.getMessage());
                asAccepted.setProperty(ProxyAEExtension.FILE_SUFFIX, RetryObject.ConnectionException.getSuffix() + "0");
                super.onDimseRQ(asAccepted, pc, dimse, rq, data);
            }
        }
    }

    private boolean spoolRequest(Association asAccepted, Dimse dimse, Attributes rq, ProxyAEExtension proxyAEE,
            Object forwardAssociationProperty) {
        return forwardAssociationProperty == null
                || proxyAEE.isAcceptDataOnFailedAssociation()
                || proxyAEE.getAttributeCoercions().findAttributeCoercion(rq.getString(Tag.AffectedSOPClassUID), dimse,
                        Role.SCU, asAccepted.getRemoteAET()) != null
                || proxyAEE.getAttributeCoercions().findAttributeCoercion(rq.getString(Tag.AffectedSOPClassUID), dimse,
                        Role.SCP, ((Association)forwardAssociationProperty).getRemoteAET()) != null
                || proxyAEE.isEnableAuditLog()
                || (forwardAssociationProperty instanceof HashMap<?, ?>)
                || (forwardAssociationProperty instanceof Association && ForwardConnectionUtils
                        .requiresMultiFrameConversion(proxyAEE,
                                ((Association) forwardAssociationProperty).getRemoteAET(),
                                rq.getString(Tag.AffectedSOPClassUID))) 
                || proxyAEE.isAssociationFromDestinationAET(asAccepted);
    }

    protected void spool(ProxyAEExtension proxyAEE, Association asAccepted, PresentationContext pc, Dimse dimse,
            Attributes cmd, PDVInputStream data, Attributes rsp) throws IOException {
        File file = createSpoolFile(proxyAEE, asAccepted);
        Attributes fmi = processInputStream(proxyAEE, asAccepted, pc, cmd, data, file);
        Object forwardAssociationProperty = asAccepted.getProperty(ProxyAEExtension.FORWARD_ASSOCIATION);
        try {
            if (forwardAssociationProperty == null && proxyAEE.isAssociationFromDestinationAET(asAccepted))
                forwardAssociationProperty = getCMoveDestinationAS(proxyAEE, asAccepted, cmd);
            if (forwardAssociationProperty != null && forwardAssociationProperty instanceof Association)
                forwardObject(asAccepted, (Association) forwardAssociationProperty, pc, cmd, file, fmi);
            else
                processForwardRules(proxyAEE, asAccepted, forwardAssociationProperty, pc, dimse, cmd, rsp, file, fmi);
        } catch (Exception e) {
            LOG.error("{}: error processing C-STORE-RQ: {}", new Object[] { asAccepted, e });
            if (file.exists())
                deleteFile(asAccepted, file);
            throw new DicomServiceException(Status.UnableToProcess, e.getCause());
        }
    }

    private static void deleteFile(Association as, File file) {
        if (file.delete())
            LOG.debug("{}: delete {}", as, file);
        else
            LOG.debug("{}: failed to delete {}", as, file);
        File info = new File(file.getPath().substring(0, file.getPath().indexOf('.')) + ".info");
        if (info.delete())
            LOG.debug("{}: delete {}", as, info);
        else
            LOG.debug("{}: failed to delete {}", as, info);
    }

    private Association getCMoveDestinationAS(ProxyAEExtension proxyAEE, Association asAccepted, Attributes cmd)
            throws DicomServiceException {
        int moveOriMsgId = cmd.getInt(Tag.MoveOriginatorMessageID, 0);
        if (moveOriMsgId == 0)
            return null;

        String originatorCallingAET = cmd.getString(Tag.MoveOriginatorApplicationEntityTitle);
        CMoveInfoObject cmoveInfoObject = proxyAEE.getCMoveInfoObject(moveOriMsgId);
        if (cmoveInfoObject == null 
                || !cmoveInfoObject.getCallingAET().equals(originatorCallingAET)
                || !cmoveInfoObject.getCalledAET().equals(asAccepted.getRemoteAET()))
            return null;

        LOG.debug("{}: found matching C-MOVE-RQ with move-originator = {} and move-destination = {}", new Object[] {
                asAccepted, cmoveInfoObject.getMoveOriginatorAET(), cmoveInfoObject.getMoveDestinationAET()});
        try {
            AAssociateRQ rq = asAccepted.getAAssociateRQ();
            ForwardRule rule = cmoveInfoObject.getRule();
            String callingAET = (rule.getUseCallingAET() == null) ? asAccepted.getCallingAET() : rule.getUseCallingAET();
            LOG.debug("{}: opening connection to move-destination {}", asAccepted, cmoveInfoObject.getMoveDestinationAET());
            Association asInvoked = ForwardConnectionUtils.openForwardAssociation(proxyAEE, asAccepted, rule, callingAET,
                    cmoveInfoObject.getMoveDestinationAET(), rq, aeCache);
            asAccepted.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION, asInvoked);
            asAccepted.setProperty(ProxyAEExtension.FORWARD_CMOVE_INFO, cmoveInfoObject);
            asAccepted.setProperty(ForwardRule.class.getName(), rule);
            proxyAEE.removeCMoveInfoObject(moveOriMsgId);
            return asInvoked;
        } catch (Exception e) {
            LOG.error("Unable to connect to {}: {}", cmoveInfoObject.getMoveDestinationAET(), e.getMessage());
            throw new DicomServiceException(Status.UnableToProcess, e.getCause());
        }
    }

    private Attributes processInputStream(ProxyAEExtension proxyAEE, Association as, PresentationContext pc,
            Attributes rq, PDVInputStream data, File file) throws FileNotFoundException, IOException {
        LOG.debug("{}: write {}", as, file);
        FileOutputStream fout = new FileOutputStream(file);
        BufferedOutputStream bout = new BufferedOutputStream(fout);
        DicomOutputStream out = new DicomOutputStream(bout, UID.ExplicitVRLittleEndian);
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        String tsuid = pc.getTransferSyntax();
        Attributes fmi = as.createFileMetaInformation(iuid, cuid, tsuid);
        Attributes attrs = data.readDataset(tsuid);
        attrs = AttributeCoercionUtils.coerceDataset(proxyAEE, as, Role.SCU, Dimse.C_STORE_RQ, attrs, rq);
        try {
            out.writeDataset(fmi, attrs);
            fout.flush();
            fout.getFD().sync();
        } finally {
            out.close();
            bout.close();
        }
        Properties prop = new Properties();
        prop.setProperty("hostname", as.getConnection().getHostname());
        String patID = attrs.getString(Tag.PatientID);
        prop.setProperty("patient-id", (patID == null || patID.length() == 0) ? "<UNKNOWN>" : patID);
        prop.setProperty("study-iuid", attrs.getString(Tag.StudyInstanceUID));
        prop.setProperty("sop-instance-uid", attrs.getString(Tag.SOPInstanceUID));
        prop.setProperty("sop-class-uid", attrs.getString(Tag.SOPClassUID));
        prop.setProperty("transfer-syntax-uid", fmi.getString(Tag.TransferSyntaxUID));
        prop.setProperty("source-aet", as.getCallingAET());
        String path = file.getPath();
        File info = new File(path.substring(0, path.length() - 5) + ".info");
        FileOutputStream infoOut = new FileOutputStream(info);
        try {
            prop.store(infoOut, null);
            infoOut.flush();
            infoOut.getFD().sync();
        } finally {
            infoOut.close();
        }
        return fmi;
    }

    private void addFileInfo(String path, String key, String value) throws IOException {
        File info = new File(path.substring(0, path.length() - 5) + ".info");
        Properties prop = new Properties();
        FileInputStream inStream = null;
        try {
            inStream = new FileInputStream(info);
            prop.load(inStream);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            inStream.close();
        }
        prop.setProperty(key, value);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(info);
            prop.store(out, null);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            out.close();
        }
    }

    private void processForwardRules(ProxyAEExtension proxyAEE, Association asAccepted,
            Object forwardAssociationProperty, PresentationContext pc, Dimse dimse, Attributes rq, Attributes rsp,
            File file, Attributes fmi) throws IOException, DicomServiceException, ConfigurationException {
        List<ForwardRule> forwardRules = ForwardRuleUtils.filterForwardRulesOnDimseRQ(
                proxyAEE.getCurrentForwardRules(asAccepted), rq.getString(dimse.tagOfSOPClassUID()), Dimse.C_STORE_RQ);
        if (forwardRules.size() == 0)
            throw new ConfigurationException("no matching forward rule");

        if (forwardRules.size() == 1 && forwardRules.get(0).getDestinationAETitles().size() == 1)
            processSingleForwardDestination(asAccepted, forwardAssociationProperty, pc, rq, file, fmi, proxyAEE,
                    forwardRules.get(0));
        else {
            List<String> prevDestinationAETs = new ArrayList<String>();
            for (ForwardRule rule : forwardRules) {
                List<String> destinationAETs = new ArrayList<String>();
                if (rule.containsTemplateURI()) {
                    Attributes data = proxyAEE.parseAttributesWithLazyBulkData(asAccepted, file);
                    destinationAETs = ForwardRuleUtils.getDestinationAETsFromForwardRule(asAccepted, rule, data);
                } else
                    destinationAETs = ForwardRuleUtils.getDestinationAETsFromForwardRule(asAccepted, rule, null);
                for (String calledAET : destinationAETs) {
                    if (prevDestinationAETs.contains(calledAET)) {
                        LOG.info("{}: Found previously used destination AET {} in rule {}, will not send data again", 
                                new Object[]{asAccepted, calledAET, rule.getCommonName()});
                        LOG.info("{}: Please check configured forward rules for overlapping time with duplicate destination AETs");
                        continue;
                    }
                    if (rule.getUseCallingAET() != null)
                        addFileInfo(file.getPath(), "use-calling-aet", rule.getUseCallingAET());
                    createMappedFileCopy(proxyAEE, asAccepted, file, calledAET, ".dcm");
                }
                prevDestinationAETs.addAll(destinationAETs);
            }
            deleteFile(asAccepted, file);
            Attributes cmd = Commands.mkCStoreRSP(rq, Status.Success);
            asAccepted.writeDimseRSP(pc, cmd);
        }
    }

    private void processSingleForwardDestination(Association asAccepted, Object forwardAssociationProperty,
            PresentationContext pc, Attributes rq, File file, Attributes fmi, ProxyAEExtension proxyAEE,
            ForwardRule rule) throws DicomServiceException, IOException {
        if (rule.getUseCallingAET() != null)
            addFileInfo(file.getPath(), "use-calling-aet", rule.getUseCallingAET());
        String calledAET = rule.getDestinationAETitles().get(0);
        ForwardOption forwardOption = proxyAEE.getForwardOptions().get(calledAET);
        if (forwardOption == null || forwardOption.getSchedule().isNow(new GregorianCalendar())) {
            String callingAET = (rule.getUseCallingAET() == null) ? asAccepted.getCallingAET() : rule.getUseCallingAET();
            Association asInvoked = getSingleForwardDestination(asAccepted, callingAET, calledAET,
                    ForwardConnectionUtils.copyOfMatchingAAssociateRQ(asAccepted), forwardAssociationProperty, proxyAEE, rule);
            if (asInvoked != null) {
                asAccepted.setProperty(ProxyAEExtension.FORWARD_ASSOCIATION, asInvoked);
                if (ForwardConnectionUtils.requiresMultiFrameConversion(proxyAEE, asInvoked.getRemoteAET(), rq.getString(Tag.AffectedSOPClassUID)))
                    processMultiFrame(proxyAEE, asAccepted, asInvoked, pc, rq, file);
                else
                    forwardObject(asAccepted, asInvoked, pc, rq, file, fmi);
            } else {
                storeToCalledAETSpoolDir(proxyAEE, asAccepted, pc, rq, file, calledAET);
            }
        } else {
            asAccepted.setProperty(ProxyAEExtension.FILE_SUFFIX, ".dcm");
            storeToCalledAETSpoolDir(proxyAEE, asAccepted, pc, rq, file, calledAET);
        }
    }

    private void storeToCalledAETSpoolDir(ProxyAEExtension proxyAEE, Association asAccepted, PresentationContext pc,
            Attributes rq, File file, String calledAET) throws IOException, DicomServiceException {
        File dir = new File(proxyAEE.getCStoreDirectoryPath(), calledAET);
        dir.mkdir();
        String fileName = file.getName();
        File dst = new File(dir, fileName.substring(0, fileName.lastIndexOf('.')).concat(
                (String) asAccepted.getProperty(ProxyAEExtension.FILE_SUFFIX)));
        LOG.debug("{}: rename {} to {}", new Object[]{asAccepted, file.getPath(), dst.getPath()});
        if (file.renameTo(dst)) {
            File infoFile = new File(proxyAEE.getCStoreDirectoryPath(), file.getName().substring(0,
                    file.getName().indexOf('.')) + ".info");
            File infoDst = new File(dir, infoFile.getName());
            infoFile.renameTo(infoDst);
            asAccepted.writeDimseRSP(pc, Commands.mkCStoreRSP(rq, Status.Success));
        } else {
            LOG.error("{}: failed to rename {} to {}", new Object[] { asAccepted, file, dst });
            throw new DicomServiceException(Status.OutOfResources);
        }
    }

    private Association getSingleForwardDestination(Association as, String callingAET, String calledAET,
            AAssociateRQ rq, Object forwardAssociationProperty, ProxyAEExtension pae, ForwardRule rule) {
        return (forwardAssociationProperty == null)
            ? newForwardAssociation(as, callingAET, calledAET, rq, pae, new HashMap<String, Association>(1), rule)
            : (forwardAssociationProperty instanceof Association)
                ? (Association) forwardAssociationProperty
                : getAssociationFromHashMap(as, callingAET, calledAET, rq, forwardAssociationProperty, pae, rule);
    }

    private Association getAssociationFromHashMap(Association as, String callingAET, String calledAET,
            AAssociateRQ rq, Object forwardAssociationProperty, ProxyAEExtension pae, ForwardRule rule) {
        @SuppressWarnings("unchecked")
        HashMap<String, Association> fwdAssocs = (HashMap<String, Association>) forwardAssociationProperty;
        return (fwdAssocs.containsKey(calledAET))
            ? fwdAssocs.get(calledAET)
            : newForwardAssociation(as, callingAET, calledAET, rq, pae, fwdAssocs, rule);
    }

    private Association newForwardAssociation(Association as, String callingAET, String calledAET, AAssociateRQ rq,
            ProxyAEExtension proxyAEE, HashMap<String, Association> fwdAssocs, ForwardRule rule) {
        rq.setCallingAET(callingAET);
        rq.setCalledAET(calledAET);
        Association asInvoked = null;
        try {
            asInvoked = ForwardConnectionUtils.openForwardAssociation(proxyAEE, as, rule, callingAET, calledAET, rq, aeCache);
        } catch (GeneralSecurityException e) {
            LOG.error("Failed to create SSL context: ", e.getMessage());
            as.setProperty(ProxyAEExtension.FILE_SUFFIX, RetryObject.GeneralSecurityException.getSuffix() + "0");
        } catch (ConfigurationException e) {
            LOG.error("Unable to load configuration for destination AET: ", e.getMessage());
            as.setProperty(ProxyAEExtension.FILE_SUFFIX, RetryObject.ConfigurationException.getSuffix() + "0");
        } catch (Exception e) {
            LOG.error("Unable to connect to {}: {}", new Object[] { calledAET, e.getMessage() });
            as.setProperty(ProxyAEExtension.FILE_SUFFIX, RetryObject.ConnectionException.getSuffix() + "0");
        }
        return asInvoked;
    }

    protected File createSpoolFile(ProxyAEExtension proxyAEE, Association as) throws DicomServiceException {
        try {
            return File.createTempFile("dcm", ".part", proxyAEE.getCStoreDirectoryPath());
        } catch (Exception e) {
            LOG.error(as + ": failed to create temp file: " + e.getMessage());
            if(LOG.isDebugEnabled())
                e.printStackTrace();
            throw new DicomServiceException(Status.OutOfResources, e.getCause());
        }
    }

    protected void forwardObject(Association asAccepted, Association asInvoked, PresentationContext pc, Attributes rq,
            File dataFile, Attributes fmi) throws IOException {
        ProxyAEExtension proxyAEE = asAccepted.getApplicationEntity().getAEExtension(ProxyAEExtension.class);
        if (ForwardConnectionUtils.requiresMultiFrameConversion(proxyAEE, asInvoked.getRemoteAET(),
                rq.getString(Tag.AffectedSOPClassUID))) {
            processMultiFrame(proxyAEE, asAccepted, asInvoked, pc, rq, dataFile);
            return;
        }
        Attributes attrs = proxyAEE.parseAttributesWithLazyBulkData(asAccepted, dataFile);
        attrs = AttributeCoercionUtils.coerceDataset(proxyAEE, asInvoked, Role.SCP, Dimse.C_STORE_RQ, attrs, rq);
        File logFile = null;
        try {
            if (proxyAEE.isEnableAuditLog()) {
                String sourceAET = fmi.getString(Tag.SourceApplicationEntityTitle);
                Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, dataFile);
                LogUtils.createStartLogFile(proxyAEE, AuditDirectory.TRANSFERRED, sourceAET, asInvoked.getRemoteAET(),
                        asInvoked.getConnection().getHostname(), prop, 0);
                logFile = LogUtils.writeLogFile(proxyAEE, AuditDirectory.TRANSFERRED, sourceAET,
                        asInvoked.getRemoteAET(), prop, dataFile.length(), 0);
            }
            forward(proxyAEE, asAccepted, asInvoked, pc, rq, new DataWriterAdapter(attrs), -1, logFile, dataFile, null);
        } catch (Exception e) {
            if (logFile != null)
                logFile.delete();
            LOG.error("{}: error forwarding object {}: {}", new Object[] { asAccepted, dataFile, e.getMessage() });
            if (proxyAEE.isAcceptDataOnFailedAssociation()) {
                asAccepted.setProperty(ProxyAEExtension.FILE_SUFFIX, ".dcm");
                storeToCalledAETSpoolDir(proxyAEE, asAccepted, pc, rq, dataFile, asInvoked.getCalledAET());
            } else
                throw new DicomServiceException(Status.UnableToProcess, e.getCause());
        }
    }

    private void processMultiFrame(ProxyAEExtension proxyAEE, Association asAccepted, Association asInvoked,
            PresentationContext pc, Attributes rq, File dataFile) throws IOException {
        Attributes src;
        DicomInputStream dis = new DicomInputStream(dataFile);
        try {
            dis.setIncludeBulkData(IncludeBulkData.URI);
            src = dis.readDataset(-1, -1);
        } finally {
            dis.close();
        }
        MultiframeExtractor extractor = new MultiframeExtractor();
        int n = src.getInt(Tag.NumberOfFrames, 1);
        long t = 0;
        boolean log = true;
        Attributes forwardRq = new Attributes(rq);
        String sourceUID = src.getString(Tag.SOPInstanceUID);
        for (int frameNumber = n - 1; frameNumber >= 0; --frameNumber) {
            File logFile = null;
            try {
                long t1 = System.currentTimeMillis();
                Attributes attrs = extractor.extract(src, frameNumber);
                long t2 = System.currentTimeMillis();
                t = t + t2 - t1;
                forwardRq.setString(Tag.AffectedSOPInstanceUID, VR.UI, attrs.getString(Tag.SOPInstanceUID));
                forwardRq.setString(Tag.AffectedSOPClassUID, VR.UI, attrs.getString(Tag.SOPClassUID));
                if (proxyAEE.isEnableAuditLog()) {
                    Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, dataFile);
                    String sourceAET = prop.getProperty("source-aet");
                    LogUtils.createStartLogFile(proxyAEE, AuditDirectory.TRANSFERRED, sourceAET,
                            asInvoked.getRemoteAET(), asInvoked.getConnection().getHostname(), prop, 0);
                    logFile = LogUtils.writeLogFile(proxyAEE, AuditDirectory.TRANSFERRED, sourceAET,
                            asInvoked.getRemoteAET(), prop, attrs.calcLength(DicomEncodingOptions.DEFAULT, true), 0);
                }
                forward(proxyAEE, asAccepted, asInvoked, pc, forwardRq, new DataWriterAdapter(attrs), frameNumber,
                        logFile, dataFile, sourceUID);
            } catch (Exception e) {
                if (logFile != null)
                    logFile.delete();
                log = false;
                if (LOG.isDebugEnabled())
                    e.printStackTrace();
                if (proxyAEE.isAcceptDataOnFailedAssociation() && dataFile.exists()) {
                    asAccepted.setProperty(ProxyAEExtension.FILE_SUFFIX, RetryObject.Exception.getSuffix() + "0");
                    storeToCalledAETSpoolDir(proxyAEE, asAccepted, pc, forwardRq, dataFile, asInvoked.getCalledAET());
                    break;
                } else {
                    LOG.error("{}: Error forwarding single-frame from multi-frame object: {}", new Object[] {
                            asAccepted, e.getMessage() });
                    Attributes rsp = Commands.mkCStoreRSP(rq, Status.UnableToProcess);
                    asAccepted.writeDimseRSP(pc, rsp);
                    break;
                }
            }
        }
        if (log)
            LOG.info("{}: extracted {} frames from multi-frame object {} in {}sec", new Object[] { asAccepted, n,
                    sourceUID, t / 1000F });
    }

    protected static void createMappedFileCopy(ProxyAEExtension proxyAEE, Association as, File file, String calledAET,
            String suffix) throws IOException {
        FileChannel source = null;
        FileChannel destination = null;
        File dir = new File(proxyAEE.getCStoreDirectoryPath(), calledAET);
        dir.mkdir();
        File dst = new File(dir, file.getName().substring(0, file.getName().lastIndexOf('.')).concat(suffix));
        LOG.debug("{}: copy {} to {}", new Object[] { as, file.getPath(), dst.getPath() });
        FileInputStream in = new FileInputStream(file);
        source = in.getChannel();
        FileOutputStream out = new FileOutputStream(dst);
        destination = out.getChannel();
        try {
            destination.transferFrom(source, 0, source.size());
            out.flush();
            out.getFD().sync();
        } finally {
            destination.close();
            out.close();
            in.close();
        }
        File infoFile = new File(proxyAEE.getCStoreDirectoryPath(), file.getName().substring(0,
                file.getName().indexOf('.')) + ".info");
        File infoDst = new File(dir, infoFile.getName());
        LOG.debug("{}: copy {} to {}", new Object[] { as, infoFile.getPath(), infoDst.getPath() });
        FileInputStream infoIn = new FileInputStream(infoFile);
        source = infoIn.getChannel();
        FileOutputStream infoOut = new FileOutputStream(infoDst);
        destination = infoOut.getChannel();
        try {
            destination.transferFrom(source, 0, source.size());
            infoOut.flush();
            infoOut.getFD().sync();
        } finally {
            destination.close();
            infoOut.close();
            infoIn.close();
        }
    }

    private static void forward(final ProxyAEExtension proxyAEE, final Association asAccepted, Association asInvoked,
            final PresentationContext pc, final Attributes rq, DataWriter data, final int frame,
            final File logFile, final File dataFile, final String sourceIUID) throws IOException, InterruptedException {
        final String tsuid = pc.getTransferSyntax();
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        final String calledAET = asInvoked.getCalledAET();
        int priority = rq.getInt(Tag.Priority, 0);
        final int msgId = rq.getInt(Tag.MessageID, 0);
        final CMoveInfoObject info = (CMoveInfoObject) asAccepted.getProperty(ProxyAEExtension.FORWARD_CMOVE_INFO);
        int newMsgId = msgId;
        if (info != null || asAccepted.isRequestor() || sourceIUID != null)
            newMsgId = asInvoked.nextMessageID();
        DimseRSPHandler rspHandler = new DimseRSPHandler(newMsgId) {

            // onClose can be called in a separate thread, e.g. by network layer
            // need to make sure to writeDimseRSP only once
            boolean isClosed = false;

            @Override
            synchronized public void onDimseRSP(Association asInvoked, Attributes cmd, Attributes data) {
                if (!isClosed) {
                    super.onDimseRSP(asInvoked, cmd, data);
                    if (frame > 0)
                        return;

                    try {
                        if (!asInvoked.isRequestor() || info != null || sourceIUID != null)
                            cmd.setInt(Tag.MessageIDBeingRespondedTo, VR.US, msgId);
                        if (sourceIUID != null)
                            cmd.setString(Tag.AffectedSOPInstanceUID, VR.UI, sourceIUID);
                        asAccepted.writeDimseRSP(pc, cmd, data);
                    } catch (IOException e) {
                        LOG.error(asInvoked + ": Failed to forward C-STORE RSP: " + e.getMessage());
                    } finally {
                        if (cmd.getInt(Tag.Status, -1) == Status.Success && dataFile != null && dataFile.exists())
                            deleteFile(asAccepted, dataFile);
                    }
                }
            }

            @Override
            synchronized public void onClose(Association as) {
                isClosed = true;
                if (logFile != null)
                    logFile.delete();
                super.onClose(as);
                Attributes cmd = new Attributes();
                if (dataFile != null && dataFile.exists() && proxyAEE.isAcceptDataOnFailedAssociation())
                    try {
                        String suffix = RetryObject.ConnectionException.getSuffix() + "0";
                        createMappedFileCopy(proxyAEE, asAccepted, dataFile, calledAET, suffix);
                        cmd = Commands.mkCStoreRSP(rq, Status.Success);
                    } catch (Exception e) {
                        LOG.error("{}: error saving file {}: {}", new Object[]{as, dataFile.getPath(), e.getMessage()});
                        if(LOG.isDebugEnabled())
                            e.printStackTrace();
                    }
                else
                    cmd = Commands.mkCStoreRSP(rq, Status.UnableToProcess);
                if (dataFile != null && dataFile.exists())
                    deleteFile(asAccepted, dataFile);
                if (!as.isRequestor() || info != null)
                    cmd.setInt(Tag.MessageIDBeingRespondedTo, VR.US, msgId);
                try {
                    asAccepted.writeDimseRSP(pc, cmd);
                } catch (IOException e) {
                    LOG.error(as + ": Failed to forward C-STORE RSP: " + e.getMessage());
                }
            }
        };

        if (info != null)
            asInvoked.cstore(cuid, iuid, priority, info.getMoveOriginatorAET(), info.getSourceMsgId(), data,
                    ForwardConnectionUtils.getMatchingTsuid(asInvoked, tsuid, cuid), rspHandler);
        else
            asInvoked.cstore(cuid, iuid, priority, data,
                    ForwardConnectionUtils.getMatchingTsuid(asInvoked, tsuid, cuid), rspHandler);
    }
}
