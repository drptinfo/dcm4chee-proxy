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

package org.dcm4chee.proxy.forward;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;

import org.dcm4che.conf.api.ApplicationEntityCache;
import org.dcm4che.conf.api.AttributeCoercion;
import org.dcm4che.conf.api.ConfigurationException;
import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.emf.MultiframeExtractor;
import org.dcm4che.io.DicomEncodingOptions;
import org.dcm4che.io.DicomInputStream;
import org.dcm4che.io.DicomInputStream.IncludeBulkData;
import org.dcm4che.net.ApplicationEntity;
import org.dcm4che.net.Association;
import org.dcm4che.net.AssociationStateException;
import org.dcm4che.net.DataWriterAdapter;
import org.dcm4che.net.Dimse;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.IncompatibleConnectionException;
import org.dcm4che.net.NoPresentationContextException;
import org.dcm4che.net.Status;
import org.dcm4che.net.TransferCapability.Role;
import org.dcm4che.net.pdu.AAbort;
import org.dcm4che.net.pdu.AAssociateRJ;
import org.dcm4che.net.pdu.AAssociateRQ;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4chee.proxy.common.AuditDirectory;
import org.dcm4chee.proxy.common.RetryObject;
import org.dcm4chee.proxy.conf.ForwardOption;
import org.dcm4chee.proxy.conf.ProxyAEExtension;
import org.dcm4chee.proxy.conf.ProxyDeviceExtension;
import org.dcm4chee.proxy.conf.Retry;
import org.dcm4chee.proxy.utils.AttributeCoercionUtils;
import org.dcm4chee.proxy.utils.ForwardConnectionUtils;
import org.dcm4chee.proxy.utils.InfoFileUtils;
import org.dcm4chee.proxy.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Michael Backhaus <michael.backaus@agfa.com>
 */
public class ForwardFiles {

    protected static final Logger LOG = LoggerFactory.getLogger(ForwardFiles.class);

    private ApplicationEntityCache aeCache;

    public ForwardFiles(ApplicationEntityCache aeCache) {
        this.aeCache = aeCache;
    }

    public void execute(ApplicationEntity ae) {
        final ProxyAEExtension proxyAEE = ae.getAEExtension(ProxyAEExtension.class);
        final HashMap<String, ForwardOption> forwardOptions = proxyAEE.getForwardOptions();
        try {
            processCStore(proxyAEE, forwardOptions);
            processNAction(proxyAEE, forwardOptions);
            ((ProxyDeviceExtension) proxyAEE.getApplicationEntity().getDevice()
                    .getDeviceExtension(ProxyDeviceExtension.class)).getFileForwardingExecutor().execute(
                    new Runnable() {

                        @Override
                        public void run() {
                            try {
                                processNCreate(proxyAEE, forwardOptions);
                            } catch (IOException e) {
                                LOG.error("Error processing scheduled N-CREATE files: {}", e.getMessage());
                                if (LOG.isDebugEnabled())
                                    e.printStackTrace();
                            }
                            try {
                                processNSet(proxyAEE, forwardOptions);
                            } catch (IOException e) {
                                LOG.error("Error processing scheduled N-SET files: {}", e.getMessage());
                                if (LOG.isDebugEnabled())
                                    e.printStackTrace();
                            }
                        }
                    });
        } catch (IOException e) {
            LOG.error("Error scanning spool directory: {}", e.getMessage());
            if(LOG.isDebugEnabled())
                e.printStackTrace();
        }
    }

    private void processNSet(ProxyAEExtension proxyAEE, HashMap<String, ForwardOption> forwardOptions) throws IOException {
        for (String calledAET : proxyAEE.getNSetDirectoryPath().list(dirFilter())) {
            File[] files = new File(proxyAEE.getNSetDirectoryPath(), calledAET).listFiles(fileFilter(proxyAEE, calledAET));
            if (files == null || files.length == 0)
                continue;

            LOG.debug("Processing schedule N-SET data for {} ...", calledAET);
            if (!forwardOptions.keySet().contains(calledAET)) {
                // process destinations without forward schedule
                LOG.debug("No forward schedule for {}, sending existing N-SET data now", calledAET);
                startForwardScheduledMPPS(proxyAEE, files, calledAET, "nset");
            } else
                for (Entry<String, ForwardOption> entry : forwardOptions.entrySet()) {
                    boolean isMatchingAET = calledAET.equals(entry.getKey());
                    if (isMatchingAET && entry.getValue().getSchedule().isNow(new GregorianCalendar())) {
                        LOG.debug("Found currently active forward schedule for {}, sending N-SET data now", calledAET);
                        startForwardScheduledMPPS(proxyAEE, files, calledAET, "nset");
                    } else if (isMatchingAET) {
                        LOG.debug("Found forward schedule for {}, but is inactive (days={}, hours={})", new Object[] {
                                calledAET, entry.getValue().getSchedule().getDays(),
                                entry.getValue().getSchedule().getHours() });
                    }
                }
        }
    }

    private void processNCreate(ProxyAEExtension proxyAEE, HashMap<String, ForwardOption> forwardOptions) throws IOException {
        for (String calledAET : proxyAEE.getNCreateDirectoryPath().list(dirFilter())) {
            File[] files = new File(proxyAEE.getNCreateDirectoryPath(), calledAET).listFiles(fileFilter(proxyAEE,
                    calledAET));
            if (files == null || files.length == 0)
                continue;

            LOG.debug("Processing schedule N-CREATE data for {} ...", calledAET);
            if (!forwardOptions.keySet().contains(calledAET)) {
                // process destinations without forward schedule
                LOG.debug("No forward schedule for {}, sending existing N-CREATE data now", calledAET);
                startForwardScheduledMPPS(proxyAEE, files, calledAET, "ncreate");
            } else
                for (Entry<String, ForwardOption> entry : forwardOptions.entrySet()) {
                    boolean isMatchingAET = calledAET.equals(entry.getKey());
                    if (isMatchingAET && entry.getValue().getSchedule().isNow(new GregorianCalendar())) {
                        LOG.debug("Found currently active forward schedule for {}, sending existing N-CREATE data now",
                                calledAET);
                        startForwardScheduledMPPS(proxyAEE, files, calledAET, "ncreate");
                    } else if (isMatchingAET) {
                        LOG.debug("Found forward schedule for {}, but is inactive (days={}, hours={})", new Object[] {
                                calledAET, entry.getValue().getSchedule().getDays(),
                                entry.getValue().getSchedule().getHours() });
                    }
                }
        }
    }

    private void processNAction(ProxyAEExtension proxyAEE, HashMap<String, ForwardOption> forwardOptions) throws IOException {
        for (String calledAET : proxyAEE.getNactionDirectoryPath().list(dirFilter())) {
            File dir = new File(proxyAEE.getNactionDirectoryPath(), calledAET);
            File[] files = dir.listFiles(fileFilter(proxyAEE, calledAET));
            if (files == null || files.length == 0)
                continue;

            LOG.debug("Processing schedule N-ACTION data ...");
            if (!forwardOptions.keySet().contains(calledAET)) {
                // process destinations without forward schedule
                LOG.debug("No forward schedule for {}, sending existing N-ACTION data now", calledAET);
                startForwardScheduledNAction(proxyAEE, calledAET, files);
            } else
                for (Entry<String, ForwardOption> entry : forwardOptions.entrySet()) {
                    boolean isMatchingAET = calledAET.equals(entry.getKey());
                    if (isMatchingAET && entry.getValue().getSchedule().isNow(new GregorianCalendar())) {
                        LOG.debug("Found currently active forward schedule for {}, sending existing N-ACTION data now",
                                calledAET);
                        startForwardScheduledNAction(proxyAEE, calledAET, files);
                    } else if (isMatchingAET) {
                        LOG.debug("Found forward schedule for {}, but is inactive (days={}, hours={})", new Object[] {
                                calledAET, entry.getValue().getSchedule().getDays(),
                                entry.getValue().getSchedule().getHours() });
                    }
                }
        }
    }

    private void processCStore(ProxyAEExtension proxyAEE, HashMap<String, ForwardOption> forwardOptions) throws IOException {
        for (String calledAET : proxyAEE.getCStoreDirectoryPath().list(dirFilter())) {
            File dir = new File(proxyAEE.getCStoreDirectoryPath(), calledAET);
            File[] files = dir.listFiles(fileFilter(proxyAEE, calledAET));
            if (files == null || files.length == 0)
                continue;

            LOG.debug("Processing schedule C-STORE data ...");
            if (!forwardOptions.keySet().contains(calledAET)) {
                // process destinations without forward schedule
                LOG.debug("No forward schedule for {}, sending existing C-STORE data now", calledAET);
                startForwardScheduledCStoreFiles(proxyAEE, calledAET, files);
            } else
                for (Entry<String, ForwardOption> entry : forwardOptions.entrySet()) {
                    boolean isMatchingAET = calledAET.equals(entry.getKey());
                    if (isMatchingAET && entry.getValue().getSchedule().isNow(new GregorianCalendar())) {
                        LOG.debug("Found currently active forward schedule for {}, sending existing C-STORE data now",
                                calledAET);
                        startForwardScheduledCStoreFiles(proxyAEE, calledAET, files);
                    } else if (isMatchingAET) {
                        LOG.debug("Found forward schedule for {}, but is inactive (days={}, hours={})", new Object[] {
                                calledAET, entry.getValue().getSchedule().getDays(),
                                entry.getValue().getSchedule().getHours() });
                    }
                }
        }
    }

    private FileFilter fileFilter(final ProxyAEExtension proxyAEE, final String calledAET) {
        final long now = System.currentTimeMillis();
        return new FileFilter() {
    
            @Override
            public boolean accept(File file) {
                String path = file.getPath();
                int interval = proxyAEE.getApplicationEntity().getDevice()
                        .getDeviceExtension(ProxyDeviceExtension.class).getSchedulerInterval();
                if (path.endsWith(".dcm")) {
                    if (now > (file.lastModified() + interval))
                        return true;
                    else
                        return false;
                }
    
                if (path.endsWith(".part") || path.endsWith(".snd") || path.endsWith(".info"))
                    return false;
    
                try {
                    LOG.debug("Get matching retry for file " + file.getPath());
                    String suffix = path.substring(path.lastIndexOf('.'));
                    Retry matchingRetry = getMatchingRetry(proxyAEE, suffix);
                    if (matchingRetry == null)
                        if (proxyAEE.isDeleteFailedDataWithoutRetryConfiguration())
                            deleteFailedFile(proxyAEE, calledAET, file,
                                    ": delete files without retry configuration is ENABLED", 0);
                        else
                            moveToNoRetryPath(proxyAEE, calledAET, file, ": delete files without retry configuration is DISABLED");
                    else if (checkNumberOfRetries(proxyAEE, matchingRetry, suffix, file, calledAET)
                            && checkSendFileDelay(now, file, matchingRetry))
                        return true;
                } catch (IndexOutOfBoundsException e) {
                    LOG.error("Error parsing suffix of " + path);
                    try {
                        moveToNoRetryPath(proxyAEE, calledAET, file, "(error parsing suffix)");
                    } catch (IOException e1) {
                        LOG.error("Error moving file {} to no retry directory: {}",
                                new Object[] { file.getName(), e.getMessage() });
                        if(LOG.isDebugEnabled())
                            e1.printStackTrace();
                    }
                } catch (IOException e) {
                    LOG.error("Error reading from directory: {}", e.getMessage());
                    if(LOG.isDebugEnabled())
                        e.printStackTrace();
                }
                return false;
            }
    
            private boolean checkSendFileDelay(final long now, File file, Retry matchingRetry) {
                boolean sendNow = now > (file.lastModified() + (matchingRetry.delay * 1000));
                if (sendNow)
                    LOG.debug(">> ready to send now");
                else
                    LOG.debug(">> wait until last send delay > {}sec", matchingRetry.delay);
                return sendNow;
            }
        };
    }

    private FilenameFilter dirFilter() {
        return new FilenameFilter() {
            
            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory();
            }
        };
            
    }

    private boolean checkNumberOfRetries(ProxyAEExtension proxyAEE, Retry retry, String suffix, File file,
            String calledAET) throws IOException {
        LOG.debug("Check number of previous retries for file " + file.getPath());
        int prevRetries = 0;
        String substring = suffix.substring(retry.getRetryObject().getSuffix().length());
        if (!substring.isEmpty())
            try {
                prevRetries = Integer.parseInt(substring);
                LOG.debug(">> previous retries = " + prevRetries);
            } catch (NumberFormatException e) {
                LOG.error("Error parsing number of retries in suffix of file " + file.getName());
                moveToNoRetryPath(proxyAEE, calledAET, file, ": error parsing suffix");
                return false;
            }
        boolean send = prevRetries < retry.numberOfRetries;
        LOG.debug(">> send file again = {} (max number of retries for {} = {})",
                new Object[] { send, retry.getRetryObject(), retry.numberOfRetries });
        if (!send) {
            String reason = ">> max number of retries = " + retry.getNumberOfRetries();
            if (sendToFallbackAET(proxyAEE, calledAET)) {
                moveToFallbackAetDir(proxyAEE, file, calledAET, reason);
            } else if (retry.deleteAfterFinalRetry)
                deleteFailedFile(proxyAEE, calledAET, file, reason + " and delete after final retry is ENABLED",
                        prevRetries);
            else {
                moveToNoRetryPath(proxyAEE, calledAET, file, reason);
            }
        }
        return send;
    }

    private void moveToFallbackAetDir(ProxyAEExtension proxyAEE, File file, String calledAET, String reason) throws IOException {
        String path = file.getAbsolutePath();
        if (path.contains("ncreate")) {
            File nSetFile = getMatchingNsetFile(proxyAEE, calledAET, file);
            if (nSetFile != null)
                moveToFallbackAetDir(proxyAEE, nSetFile, calledAET, reason);
        }
        File dstDir = new File(path.substring(0, path.indexOf(calledAET)) + proxyAEE.getFallbackDestinationAET());
        dstDir.mkdir();
        String fileName = file.getName();
        File dst = new File(dstDir, fileName.substring(0, fileName.indexOf(".")) + ".dcm");
        if (file.renameTo(dst))
            LOG.debug("Rename {} to {} {} and fallback AET is {}",
                    new Object[] { file, dst, reason, proxyAEE.getFallbackDestinationAET() });
        else
            LOG.error("Failed to rename {} to {}", new Object[] { file, dst });
        File infoFile = new File(path.substring(0, path.indexOf('.')) + ".info");
        File infoDst = new File(dstDir, fileName.substring(0, fileName.indexOf('.')) + ".info");
        if (infoFile.renameTo(infoDst))
            LOG.debug("Rename {} to {} {} and fallback AET is {}",
                    new Object[] { infoFile, infoDst, reason, proxyAEE.getFallbackDestinationAET() });
        else
            LOG.error("Failed to rename {} to {}", new Object[] { infoFile, infoDst });
    }

    private File getMatchingNsetFile(ProxyAEExtension proxyAEE, String calledAET, File file) throws IOException {
        File dir = new File(proxyAEE.getNSetDirectoryPath(), calledAET);
        if (!dir.exists())
            return null;

        Properties nCreateProp = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
        String sopInstanceUID = nCreateProp.getProperty("sop-instance-uid");
        File[] nSetInfoFiles = dir.listFiles(InfoFileUtils.infoFileFilter());
        for (File nSetInfoFile : nSetInfoFiles) {
            Properties nSetProp = InfoFileUtils.getPropertiesFromInfoFile(proxyAEE, nSetInfoFile.getPath());
            if (nSetProp.getProperty("sop-instance-uid").equals(sopInstanceUID))
                return new File(nSetInfoFile.getPath().substring(0, nSetInfoFile.getPath().indexOf('.')) + ".dcm");
        }
        return null;
    }

    private boolean sendToFallbackAET(ProxyAEExtension proxyAEE, String destinationAET) {
        if (proxyAEE.getFallbackDestinationAET() != null)
            if (!destinationAET.equals(proxyAEE.getFallbackDestinationAET()))
                return true;
        return false;
    }

    protected Retry getMatchingRetry(ProxyAEExtension proxyAEE, String suffix) {
        for (Retry retry : proxyAEE.getRetries()) {
            String retrySuffix = retry.getRetryObject().getSuffix();
            boolean startsWith = suffix.startsWith(retrySuffix);
            LOG.debug(">> \"{}\" starts with \"{}\" = {}", new Object[] { suffix, retrySuffix, startsWith });
            if (startsWith) {
                LOG.debug("Found matching retry configuration: " + retry.getRetryObject());
                return retry;
            }
        }
        LOG.debug("Found no matching retry configuration");
        return null;
    }

    protected void moveToNoRetryPath(ProxyAEExtension proxyAEE, String calledAET, File file, String reason) throws IOException {
        String path = file.getPath();
        if (path.contains("ncreate")) {
            File nSetFile = getMatchingNsetFile(proxyAEE, calledAET, file);
            if (nSetFile != null)
                moveToNoRetryPath(proxyAEE, calledAET, nSetFile, reason);
        }
        String spoolDirPath = proxyAEE.getSpoolDirectoryPath().getPath();
        String fileName = file.getName();
        String subPath = path.substring(path.indexOf(spoolDirPath) + spoolDirPath.length(), path.indexOf(fileName));
        File dstDir = new File(proxyAEE.getNoRetryPath().getPath() + subPath);
        dstDir.mkdirs();
        File dstFile = new File(dstDir, fileName);
        if (file.renameTo(dstFile))
            LOG.debug("Rename {} to {} {} and fallback AET is {}",
                    new Object[] { file, dstFile, reason, proxyAEE.getFallbackDestinationAET() });
        else
            LOG.error("Failed to rename {} to {}", new Object[] { file, dstFile });
        File infoFile = new File(path.substring(0, path.indexOf('.')) + ".info");
        File infoDst = new File(dstDir, fileName.substring(0, fileName.indexOf('.')) + ".info");
        if (infoFile.renameTo(infoDst))
            LOG.debug("Rename {} to {} {} and fallback AET is {}",
                    new Object[] { infoFile, infoDst, reason, proxyAEE.getFallbackDestinationAET() });
        else
            LOG.error("Failed to rename {} to {}", new Object[] { infoFile, infoDst });
    }

    private void deleteFailedFile(ProxyAEExtension proxyAEE, String calledAET, File file, String reason, Integer retry) {
        try {
            String path = file.getPath();
            Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
            if (proxyAEE.isEnableAuditLog() && path.contains("cstore")) {
                String callingAET = prop.getProperty("source-aet");
                LogUtils.createStartLogFile(proxyAEE, AuditDirectory.DELETED, callingAET, calledAET, proxyAEE
                        .getApplicationEntity().getConnections().get(0).getHostname(), prop, retry);
                LogUtils.writeLogFile(proxyAEE, AuditDirectory.DELETED, callingAET, calledAET, prop, file.length(), retry);
            }
            if (path.contains("ncreate"))
                deletePendingNSet(proxyAEE, calledAET, file, prop);
            if (file.delete())
                LOG.debug("Delete {} {}", file, reason);
            else {
                LOG.error("Failed to delete {}", file);
                return;
            }
            File infoFile = new File(file.getPath().substring(0, file.getPath().indexOf('.')) + ".info");
            if (infoFile.delete())
                LOG.debug("Delete {}", infoFile);
            else
                LOG.error("Failed to delete {}", infoFile);
        } catch (Exception e) {
            LOG.error("Failed to create log file: " + e.getMessage());
            if(LOG.isDebugEnabled())
                e.printStackTrace();
        }
    }

    private void deletePendingNSet(ProxyAEExtension proxyAEE, String calledAET, File file, Properties prop)
            throws IOException {
        File nSetDir = new File(proxyAEE.getNSetDirectoryPath(), calledAET);
        if (nSetDir.exists() && nSetDir.list() != null && nSetDir.list().length != 0) {
            String sopInstanceUID = prop.getProperty("sop-instance-uid");
            for (File nSetFile : nSetDir.listFiles(fileFilter(proxyAEE, calledAET))) {
                Properties nSetProp = InfoFileUtils.getFileInfoProperties(proxyAEE, nSetFile);
                if (nSetProp.getProperty("sop-instance-uid").equals(sopInstanceUID)) {
                    if (nSetFile.delete())
                        LOG.debug("Delete {} before deleting matching N-CREATE file {}", nSetFile, file);
                    else {
                        LOG.error("Failed to delete {}", nSetFile);
                        return;
                    }
                    File infoFile = new File(nSetFile.getPath().substring(0, nSetFile.getPath().indexOf('.')) + ".info");
                    if (infoFile.delete())
                        LOG.debug("Delete {}", infoFile);
                    else
                        LOG.error("Failed to delete {}", infoFile);
                }
            }
        }
    }

    private void startForwardScheduledMPPS(final ProxyAEExtension proxyAEE, File[] files,
            final String destinationAETitle, final String protocol) {
        final File[] sendFiles = createSendFileList(files);
        try {
            forwardScheduledMPPS(proxyAEE, sendFiles, destinationAETitle, protocol);
        } catch (IOException e) {
            LOG.error("Error forwarding scheduled MPPS " + e.getMessage());
            if (LOG.isDebugEnabled())
                e.printStackTrace();
        }
    }

    protected void forwardScheduledMPPS(ProxyAEExtension proxyAEE, File[] files, String destinationAETitle,
            String protocol) throws IOException {
        for (File file : files) {
            Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
            String callingAET = prop.containsKey("use-calling-aet") ? prop.getProperty("use-calling-aet") : prop
                    .getProperty("source-aet");
            try {
                if (protocol == "nset" && pendingNCreateForwarding(proxyAEE, destinationAETitle, file)) {
                    String prevFilePath = file.getPath();
                    File dst = new File(prevFilePath.substring(0, prevFilePath.length() - 4));
                    if (file.renameTo(dst))
                        LOG.debug("{} has pending N-CREATE-RQ, rename to {}", prevFilePath, dst);
                    else {
                        LOG.error("Error renaming {} to {}.", prevFilePath, dst);
                    }
                    continue;
                }
                AAssociateRQ rq = new AAssociateRQ();
                rq.addPresentationContext(new PresentationContext(1, UID.ModalityPerformedProcedureStepSOPClass,
                        UID.ExplicitVRLittleEndian));
                rq.setCallingAET(callingAET);
                rq.setCalledAET(destinationAETitle);
                Association as = proxyAEE.getApplicationEntity().connect(
                        aeCache.findApplicationEntity(destinationAETitle), rq);
                try {
                    if (as.isReadyForDataTransfer()) {
                        Attributes fmi = readFileMetaInformation(file);
                        forwardScheduledMPPS(proxyAEE, as, file, fmi, protocol, prop);
                    } else {
                        renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, destinationAETitle,
                                prop);
                    }
                } finally {
                    if (as != null) {
                        try {
                            as.waitForOutstandingRSP();
                            as.release();
                        } catch (InterruptedException e) {
                            LOG.error(as + ": unexpected exception: " + e.getMessage());
                            if(LOG.isDebugEnabled())
                                e.printStackTrace();
                        } catch (IOException e) {
                            LOG.error(as + ": failed to release association: " + e.getMessage());
                            if(LOG.isDebugEnabled())
                                e.printStackTrace();
                        }
                    }
                }
            } catch (IOException e) {
                LOG.error("Error connecting to {}: {} ", destinationAETitle, e.getMessage());
                renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, destinationAETitle, prop);
            } catch (InterruptedException e) {
                LOG.error("Connection exception: " + e.getMessage());
                renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, destinationAETitle, prop);
            } catch (IncompatibleConnectionException e) {
                LOG.error("Incompatible connection: " + e.getMessage());
                renameFile(proxyAEE, RetryObject.IncompatibleConnectionException.getSuffix(), file, destinationAETitle,
                        prop);
            } catch (ConfigurationException e) {
                LOG.error("Unable to load configuration: " + e.getMessage());
                renameFile(proxyAEE, RetryObject.ConfigurationException.getSuffix(), file, destinationAETitle, prop);
            } catch (GeneralSecurityException e) {
                LOG.error("Failed to create SSL context: " + e.getMessage());
                renameFile(proxyAEE, RetryObject.GeneralSecurityException.getSuffix(), file, destinationAETitle, prop);
            }
        }
    }

    private void writeFailedAuditLogMessage(ProxyAEExtension proxyAEE, File file, Attributes fmi, String calledAET,
            Properties prop) throws IOException {
        if (proxyAEE.isEnableAuditLog() && file.getPath().contains("cstore")) {
            String sourceAET = prop.getProperty("source-aet");
            int retry = getPreviousRetries(proxyAEE, file);
            LogUtils.createStartLogFile(proxyAEE, AuditDirectory.FAILED, sourceAET, calledAET, proxyAEE
                    .getApplicationEntity().getConnections().get(0).getHostname(), prop, retry);
            LogUtils.writeLogFile(proxyAEE, AuditDirectory.FAILED, sourceAET, calledAET, prop, file.length(), retry);
        }
    }

    private boolean pendingNCreateForwarding(ProxyAEExtension proxyAEE, String destinationAETitle, File file)
            throws IOException {
        File dir = new File(proxyAEE.getNCreateDirectoryPath(), destinationAETitle);
        if (!dir.exists())
            return false;

        Properties nSetProp = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
        String sopInstanceUID = nSetProp.getProperty("sop-instance-uid");
        File[] nCreateInfoFiles = dir.listFiles(InfoFileUtils.infoFileFilter());
        for (File nCreateInfoFile : nCreateInfoFiles) {
            Properties nCreateProp = InfoFileUtils.getPropertiesFromInfoFile(proxyAEE,
                    nCreateInfoFile.getAbsolutePath());
            if (nCreateProp.getProperty("sop-instance-uid").equals(sopInstanceUID))
                return true;
        }
        return false;
    }

    private void forwardScheduledMPPS(final ProxyAEExtension proxyAEE, final Association as, final File file,
            final Attributes fmi, String protocol, final Properties prop) throws IOException, InterruptedException {
        String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
        String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
        String tsuid = UID.ExplicitVRLittleEndian;
        DicomInputStream in = new DicomInputStream(file);
        Attributes attrs = in.readDataset(-1, -1);
        DimseRSPHandler rspHandler = new DimseRSPHandler(as.nextMessageID()) {

            @Override
            public void onDimseRSP(Association as, Attributes cmd, Attributes data) {
                super.onDimseRSP(as, cmd, data);
                int status = cmd.getInt(Tag.Status, -1);
                switch (status) {
                case Status.Success:
                    LOG.debug("{}: forwarded file {} with status {}",
                            new Object[] { as, file, Integer.toHexString(status) + 'H' });
                    deleteSendFile(as, file);
                    break;
                default: {
                    LOG.debug("{}: failed to forward file {} with error status {}",
                            new Object[] { as, file, Integer.toHexString(status) + 'H' });
                    renameFile(proxyAEE, '.' + Integer.toHexString(status) + 'H', file, as.getCalledAET(), prop);
                }
                }
            }
        };
        try {
            if (protocol == "ncreate")
                as.ncreate(cuid, iuid, attrs, tsuid, rspHandler);
            else
                as.nset(cuid, iuid, attrs, tsuid, rspHandler);
        } finally {
            in.close();
        }
    }

    private void startForwardScheduledNAction(final ProxyAEExtension proxyAEE, final String destinationAETitle,
            File[] files) {
        final File[] sendFiles = createSendFileList(files);
        ((ProxyDeviceExtension) proxyAEE.getApplicationEntity().getDevice()
                .getDeviceExtension(ProxyDeviceExtension.class)).getFileForwardingExecutor().execute(new Runnable() {

            @Override
            public void run() {
                try {
                    forwardScheduledNAction(proxyAEE, destinationAETitle, sendFiles);
                } catch (IOException e) {
                    LOG.error("Error forwarding scheduled NAction: " + e.getMessage());
                    if (LOG.isDebugEnabled())
                        e.printStackTrace();
                }
            }
        });
    }

    private File[] createSendFileList(File[] files) {
        ArrayList<File> sendFilesList = new ArrayList<File>();
        for (File file : files) {
            String prevFilePath = file.getPath();
            File snd = new File(prevFilePath + ".snd");
            if (file.renameTo(snd)) {
                LOG.debug("Rename {} to {}", prevFilePath, snd.getPath());
                sendFilesList.add(snd);
            } else
                LOG.error("Error renaming {} to {}. Skip file for now and try again on next scheduler run.",
                        prevFilePath, snd.getPath());
        }
        return sendFilesList.toArray(new File[sendFilesList.size()]);
    }

    private void forwardScheduledNAction(ProxyAEExtension proxyAEE, String calledAET, File[] files) throws IOException {
        for (File file : files) {
            Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
            String callingAET = prop.containsKey("use-calling-aet") ? prop.getProperty("use-calling-aet") : prop
                    .getProperty("source-aet");
            try {
                AAssociateRQ rq = new AAssociateRQ();
                rq.addPresentationContext(new PresentationContext(1, UID.StorageCommitmentPushModelSOPClass,
                        UID.ExplicitVRLittleEndian));
                rq.setCallingAET(callingAET);
                rq.setCalledAET(calledAET);
                Association asInvoked = proxyAEE.getApplicationEntity().connect(aeCache.findApplicationEntity(calledAET), rq);
                try {
                    if (asInvoked.isReadyForDataTransfer()) {
                        forwardScheduledNAction(proxyAEE, asInvoked, file, prop);
                    } else {
                        renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, calledAET, prop);
                    }
                } finally {
                    if (asInvoked != null) {
                        try {
                            asInvoked.waitForOutstandingRSP();
                            asInvoked.release();
                        } catch (InterruptedException e) {
                            LOG.error(asInvoked + ": unexpected exception: " + e.getMessage());
                            if(LOG.isDebugEnabled())
                                e.printStackTrace();
                        } catch (IOException e) {
                            LOG.error(asInvoked + ": failed to release association: " + e.getMessage());
                            if(LOG.isDebugEnabled())
                                e.printStackTrace();
                        }
                    }
                }
            } catch (InterruptedException e) {
                LOG.error(e.getMessage());
                renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, calledAET, prop);
            } catch (IncompatibleConnectionException e) {
                LOG.error(e.getMessage());
                renameFile(proxyAEE, RetryObject.IncompatibleConnectionException.getSuffix(), file, calledAET, prop);
            } catch (ConfigurationException e) {
                LOG.error(e.getMessage());
                renameFile(proxyAEE, RetryObject.ConfigurationException.getSuffix(), file, calledAET, prop);
            } catch (IOException e) {
                LOG.error(e.getMessage());
                renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, calledAET, prop);
            } catch (GeneralSecurityException e) {
                LOG.error(e.getMessage());
                renameFile(proxyAEE, RetryObject.GeneralSecurityException.getSuffix(), file, calledAET, prop);
            }
        }
    }

    private void forwardScheduledNAction(final ProxyAEExtension proxyAEE, final Association as, final File file,
            final Properties prop) throws IOException, InterruptedException {
        String iuid = prop.getProperty("sop-instance-uid");
        String cuid = prop.getProperty("sop-class-uid");
        String tsuid = UID.ExplicitVRLittleEndian;
        DicomInputStream in = new DicomInputStream(file);
        Attributes attrs = in.readDataset(-1, -1);
        DimseRSPHandler rspHandler = new DimseRSPHandler(as.nextMessageID()) {
            @Override
            public void onDimseRSP(Association asInvoked, Attributes cmd, Attributes data) {
                super.onDimseRSP(asInvoked, cmd, data);
                int status = cmd.getInt(Tag.Status, -1);
                switch (status) {
                case Status.Success: {
                    String fileName = file.getName();
                    String filePath = file.getPath();
                    File destDir = null;
                    try {
                        destDir = new File(proxyAEE.getNeventDirectoryPath() + proxyAEE.getSeparator()
                                + asInvoked.getCalledAET());
                    } catch (IOException e) {
                        LOG.error("{}: error creating directory {}: {}", new Object[]{as, destDir, e.getMessage()});
                        if(LOG.isDebugEnabled())
                            e.printStackTrace();
                        break;
                    }
                    destDir.mkdirs();
                    File dest = new File(destDir, fileName.substring(0, fileName.indexOf('.'))
                            + ".naction");
                    if (file.renameTo(dest)) {
                        dest.setLastModified(System.currentTimeMillis());
                        LOG.debug("{}: RENAME {} to {}", new Object[] { as, file.getPath(), dest.getPath() });
                        File infoFile = new File(filePath.substring(0, filePath.indexOf('.'))  + ".info");
                        File infoFileDest = new File(destDir, infoFile.getName());
                        if (infoFile.renameTo(infoFileDest))
                            LOG.debug("{}: RENAME {} to {}",
                                    new Object[] { as, infoFile.getPath(), infoFileDest.getPath() });
                        else
                            LOG.error("{}: failed to RENAME {} to {}", new Object[] { as, infoFile.getPath(),
                                    infoFileDest.getPath() });
                        File path = new File(file.getParent());
                        if (path.list().length == 0)
                            path.delete();
                    } else
                        LOG.error("{}: failed to RENAME {} to {}", new Object[] { as, file.getPath(), dest.getPath() });
                    break;
                }
                default: {
                    LOG.error("{}: failed to forward N-ACTION file {} with error status {}", new Object[] { as, file,
                            Integer.toHexString(status) + 'H' });
                    renameFile(proxyAEE, '.' + Integer.toHexString(status) + 'H', file, as.getCalledAET(), prop);
                }
                }
            }
        };
        try {
            as.naction(cuid, iuid, 1, attrs, tsuid, rspHandler);
        } finally {
            in.close();
        }
    }

    private void startForwardScheduledCStoreFiles(final ProxyAEExtension proxyAEE, final String calledAET,
            final File[] files) {
        ((ProxyDeviceExtension) proxyAEE.getApplicationEntity().getDevice()
                .getDeviceExtension(ProxyDeviceExtension.class)).getFileForwardingExecutor().execute(new Runnable() {

            @Override
            public void run() {
                forwardScheduledCStoreFiles(proxyAEE, calledAET, files);
            }
        });
    }

    private void forwardScheduledCStoreFiles(ProxyAEExtension proxyAEE, String calledAET, File[] files) {
        Collection<ForwardTask> forwardTasks = null;
        forwardTasks = scanFiles(proxyAEE, calledAET, files);
        for (ForwardTask ft : forwardTasks)
            try {
                processForwardTask(proxyAEE, ft);
            } catch (IOException e) {
                LOG.error("Error processing forwarding files: " + e.getMessage());
                if(LOG.isDebugEnabled())
                    e.printStackTrace();
            }
    }

    private void processForwardTask(ProxyAEExtension proxyAEE, ForwardTask ft) throws IOException {
        AAssociateRQ rq = ft.getAAssociateRQ();
        Association asInvoked = null;
        Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, ft.getFiles().get(0));
        try {
            if (proxyAEE.getForwardOptions().containsKey(rq.getCalledAET())
                    && proxyAEE.getForwardOptions().get(rq.getCalledAET()).isConvertEmf2Sf())
                ForwardConnectionUtils.addReducedTS(rq);
            asInvoked = proxyAEE.getApplicationEntity().connect(aeCache.findApplicationEntity(rq.getCalledAET()), rq);
            for (File file : ft.getFiles()) {
                prop = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
                try {
                    String cuid = prop.getProperty("sop-class-uid");
                    if (ForwardConnectionUtils.requiresMultiFrameConversion(proxyAEE, asInvoked.getCalledAET(), cuid))
                        processEmf2Sf(proxyAEE, asInvoked, prop, file);
                    else if (asInvoked.isReadyForDataTransfer()) {
                        Attributes attrs = proxyAEE.parseAttributesWithLazyBulkData(asInvoked, file);
                        AttributeCoercion ac = proxyAEE.getAttributeCoercion(asInvoked.getCalledAET(), cuid, Role.SCP,
                                Dimse.C_STORE_RQ);
                        if (ac != null)
                            attrs = AttributeCoercionUtils.coerceAttributes(asInvoked, proxyAEE, attrs, ac);
                        forwardScheduledCStoreFile(proxyAEE, asInvoked, new DataWriterAdapter(attrs), -1, file, prop, file.length());
                    } else
                        renameFile(proxyAEE, RetryObject.ConnectionException.getSuffix(), file, rq.getCalledAET(), prop);
                } catch (NoPresentationContextException npc) {
                    handleForwardException(proxyAEE, asInvoked, file, npc,
                            RetryObject.NoPresentationContextException.getSuffix(), prop, true);
                } catch (AssociationStateException ass) {
                    handleForwardException(proxyAEE, asInvoked, file, ass,
                            RetryObject.AssociationStateException.getSuffix(), prop, true);
                } catch (IOException ioe) {
                    handleForwardException(proxyAEE, asInvoked, file, ioe, RetryObject.ConnectionException.getSuffix(),
                            prop, true);
                } catch (Exception e) {
                    LOG.error("Unexpected exception: ", e.getMessage());
                    handleForwardException(proxyAEE, asInvoked, file, e, RetryObject.Exception.getSuffix(), prop, true);
                }
            }
        } catch (ConfigurationException ce) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, ce, RetryObject.ConfigurationException.getSuffix(),
                    prop);
        } catch (AAssociateRJ rj) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, rj, RetryObject.AAssociateRJ.getSuffix(), prop);
        } catch (AAbort aa) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, aa, RetryObject.AAbort.getSuffix(), prop);
        } catch (IOException e) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, e, RetryObject.ConnectionException.getSuffix(), prop);
        } catch (InterruptedException e) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, e, RetryObject.ConnectionException.getSuffix(), prop);
        } catch (IncompatibleConnectionException e) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, e,
                    RetryObject.IncompatibleConnectionException.getSuffix(), prop);
        } catch (GeneralSecurityException e) {
            handleProcessForwardTaskException(proxyAEE, rq, ft, e, RetryObject.GeneralSecurityException.getSuffix(),
                    prop);
        } finally {
            if (asInvoked != null) {
                try {
                    asInvoked.waitForOutstandingRSP();
                    asInvoked.release();
                } catch (InterruptedException e) {
                    LOG.error(asInvoked + ": unexpected exception: " + e.getMessage());
                    if(LOG.isDebugEnabled())
                        e.printStackTrace();
                } catch (IOException e) {
                    LOG.error(asInvoked + ": failed to release association: " + e.getMessage());
                    if(LOG.isDebugEnabled())
                        e.printStackTrace();
                }
            }
        }
    }

    private void processEmf2Sf(ProxyAEExtension proxyAEE, Association asInvoked, Properties prop, File file)
            throws IOException, InterruptedException {
        Attributes src;
        DicomInputStream dis = new DicomInputStream(file);
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
        for (int frameNumber = n - 1; frameNumber >= 0; --frameNumber) {
            long t1 = System.currentTimeMillis();
            Attributes attrs = extractor.extract(src, frameNumber);
            long t2 = System.currentTimeMillis();
            t = t + t2 - t1;
            long length = attrs.calcLength(DicomEncodingOptions.DEFAULT, true);
            if (asInvoked.isReadyForDataTransfer()) {
                prop.setProperty("sop-instance-uid", attrs.getString(Tag.SOPInstanceUID));
                prop.setProperty("sop-class-uid", attrs.getString(Tag.SOPClassUID));
                forwardScheduledCStoreFile(proxyAEE, asInvoked, new DataWriterAdapter(attrs), frameNumber, file, prop, length);
            } else {
                log = false;
                break;
            }
        }
        if (log)
            LOG.info("{}: extracted {} frames from multi-frame object {} in {}sec",
                    new Object[] { asInvoked, n, src.getString(Tag.SOPInstanceUID), t / 1000F });
    }

    private void forwardScheduledCStoreFile(final ProxyAEExtension proxyAEE, final Association asInvoked,
            DataWriterAdapter data, final int frame, final File file, final Properties prop, final long fileSize) throws IOException,
            InterruptedException {
            final String cuid = prop.getProperty("sop-class-uid");
            final String iuid = prop.getProperty("sop-instance-uid");
            final String tsuid = prop.getProperty("transfer-syntax-uid");
            DimseRSPHandler rspHandler = new DimseRSPHandler(asInvoked.nextMessageID()) {
    
                @Override
                public void onDimseRSP(Association asInvoked, Attributes cmd, Attributes data) {
                    super.onDimseRSP(asInvoked, cmd, data);
                    int status = cmd.getInt(Tag.Status, -1);
                    switch (status) {
                    case Status.Success:
                    case Status.CoercionOfDataElements: {
                        if (proxyAEE.isEnableAuditLog())
                            LogUtils.writeLogFile(proxyAEE, AuditDirectory.TRANSFERRED, asInvoked.getCallingAET(),
                                    asInvoked.getRemoteAET(), prop, fileSize, -1);
                        if (frame > 0)
                            return;
    
                        deleteSendFile(asInvoked, file);
                        break;
                    }
                    default: {
                        LOG.debug("{}: failed to forward file {} with error status {}", new Object[] { asInvoked, file,
                                Integer.toHexString(status) + 'H' });
                        try {
                            renameFile(proxyAEE, '.' + Integer.toHexString(status) + 'H', file, asInvoked.getCalledAET(),
                                    prop);
                        } catch (Exception e) {
                            LOG.error("{}: error renaming file {}: {}",
                                    new Object[] { asInvoked, file.getPath(), e.getMessage() });
                            if(LOG.isDebugEnabled())
                                e.printStackTrace();
                        }
                    }
                    }
                }
            };
            if (proxyAEE.isEnableAuditLog()) {
                String sourceAET = prop.getProperty("source-aet");
                LogUtils.createStartLogFile(proxyAEE, AuditDirectory.TRANSFERRED, sourceAET, asInvoked.getRemoteAET(),
                        asInvoked.getConnection().getHostname(), prop, 0);
            }
            asInvoked.cstore(cuid, iuid, 0, data, tsuid, rspHandler);
    }

    private Integer getPreviousRetries(ProxyAEExtension proxyAEE, File file) {
        String suffix = file.getName().substring(file.getName().lastIndexOf('.'));
        Retry matchingRetry = getMatchingRetry(proxyAEE, suffix);
        if (matchingRetry != null) {
            String substring = suffix.substring(matchingRetry.getRetryObject().getSuffix().length());
            if (!substring.isEmpty())
                try {
                    return Integer.parseInt(substring);
                } catch (NumberFormatException e) {
                    LOG.error("Error parsing number of retries in suffix of file " + file.getName());
                    if(LOG.isDebugEnabled())
                        e.printStackTrace();
                }
        }
        return 1;
    }

    private Collection<ForwardTask> scanFiles(ProxyAEExtension proxyAEE, String calledAET, File[] files) {
        HashMap<String, ForwardTask> map = new HashMap<String, ForwardTask>(4);
        for (File file : files) {
            String prevFilePath = file.getPath();
            File snd = new File(prevFilePath + ".snd");
            if (file.renameTo(snd))
                LOG.debug("Rename {} to {}", prevFilePath, snd.getPath());
            else {
                LOG.error("Error renaming {} to {}. Skip file for now and try again on next scheduler run.", prevFilePath, snd.getPath());
                continue;
            }
            try {
                addFileToFwdTaskMap(proxyAEE, calledAET, snd, map);
            } catch (Exception e) {
                File prev = new File(prevFilePath);
                if (snd.renameTo(prev))
                    LOG.debug("Rename {} to {}", snd.getPath(), prev.getPath());
                else
                    LOG.debug("Error renaming {} to {}", snd.getPath(), prev.getPath());
            }
        }
        return map.values();
    }

    private void addFileToFwdTaskMap(ProxyAEExtension proxyAEE, String calledAET, File file, HashMap<String, ForwardTask> map)
            throws IOException {
        Properties prop = InfoFileUtils.getFileInfoProperties(proxyAEE, file);
        String callingAET = prop.containsKey("use-calling-aet") 
                ? prop.getProperty("use-calling-aet") 
                : prop.getProperty("source-aet");
        String cuid = prop.getProperty("sop-class-uid");
        String tsuid = prop.getProperty("transfer-syntax-uid");
        ForwardTask forwardTask = map.get(callingAET);
        if (forwardTask == null) {
            LOG.debug("Creating new forward task for Calling AET {} and Called AET {}", callingAET, calledAET);
            forwardTask = new ForwardTask(callingAET, calledAET);
            map.put(callingAET, forwardTask);
        } else {
            LOG.debug("Loaded forward task for Calling AET {} and Called AET {}", callingAET, forwardTask
                    .getAAssociateRQ().getCalledAET());
        }
        LOG.debug(
                "Add file {} to forward task for Calling AET {} and Called AET {} with SOP Class UID = {} and Transfer Syntax UID = {}",
                new Object[] { file.getPath(), callingAET, forwardTask.getAAssociateRQ().getCalledAET(), cuid, tsuid });
        forwardTask.addFile(file, cuid, tsuid);
    }

    private static Attributes readFileMetaInformation(File file) throws IOException {
        DicomInputStream in = new DicomInputStream(file);
        try {
            return in.readFileMetaInformation();
        } finally {
            in.close();
        }
    }

    private void handleForwardException(ProxyAEExtension proxyAEE, Association as, File file, Exception e,
            String suffix, Properties prop, boolean writeAuditLogMessage) throws IOException {
        LOG.error(as + ": error processing forward task: " + e.getMessage());
        as.setProperty(ProxyAEExtension.FILE_SUFFIX, suffix);
        renameFile(proxyAEE, suffix, file, as.getCalledAET(), prop);
        if (LOG.isDebugEnabled())
            e.printStackTrace();
    }

    private void handleProcessForwardTaskException(ProxyAEExtension proxyAEE, AAssociateRQ rq, ForwardTask ft,
            Exception e, String suffix, Properties prop) throws IOException {
        LOG.error("Unable to connect to {}: {}", new Object[] { ft.getAAssociateRQ().getCalledAET(), e.getMessage() });
        for (File file : ft.getFiles()) {
            renameFile(proxyAEE, suffix, file, rq.getCalledAET(), prop);
        }
    }

    private void renameFile(ProxyAEExtension proxyAEE, String suffix, File file, String calledAET, Properties prop) {
        File dst;
        String path = file.getPath();
        if (path.endsWith(".snd"))
            dst = setFileSuffix(path.substring(0, path.length() - 4), suffix);
        else
            dst = setFileSuffix(path, suffix);
        if (file.renameTo(dst)) {
            dst.setLastModified(System.currentTimeMillis());
            LOG.debug("Rename {} to {}", new Object[] { file, dst });
            try {
                writeFailedAuditLogMessage(proxyAEE, dst, null, calledAET, prop);
            } catch (IOException e) {
                LOG.error("Failed to write audit log message");
                if (LOG.isDebugEnabled())
                    e.printStackTrace();
            }
        } else {
            LOG.error("Failed to rename {} to {}", new Object[] { file, dst });
        }
    }

    private File setFileSuffix(String path, String newSuffix) {
        int indexOfNewSuffix = path.lastIndexOf(newSuffix);
        if (indexOfNewSuffix == -1)
            return new File(path + newSuffix + "1");

        int indexOfNumRetries = indexOfNewSuffix + newSuffix.length();
        int indexOfNextSuffix = path.indexOf('.', indexOfNewSuffix + 1);
        if (indexOfNextSuffix == -1) {
            String substring = path.substring(indexOfNumRetries);
            int previousNumRetries = Integer.parseInt(substring);
            return new File(path.substring(0, indexOfNumRetries) + Integer.toString(previousNumRetries + 1));
        }
        int previousNumRetries = Integer.parseInt(path.substring(indexOfNumRetries, indexOfNextSuffix));
        String substringStart = path.substring(0, indexOfNewSuffix);
        String substringEnd = path.substring(indexOfNextSuffix);
        String pathname = substringStart + substringEnd + newSuffix + Integer.toString(previousNumRetries + 1);
        return new File(pathname);
    }

    private static void deleteSendFile(Association as, File file) {
        if (file.delete())
            LOG.debug("{}: delete {}", as, file);
        else
            LOG.debug("{}: failed to delete {}", as, file);
        File infoFile = new File(file.getPath().substring(0, file.getPath().indexOf('.')) + ".info");
        if (infoFile.delete())
            LOG.debug("{}: delete {}", as, infoFile);
        else
            LOG.debug("{}: failed to delete {}", as, infoFile);
        File path = new File(file.getParent());
        if (path.list().length == 0)
            path.delete();
    }
}
