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
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
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

package org.dcm4chee.proxy.mc.net.service;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.net.Association;
import org.dcm4che.net.DataWriter;
import org.dcm4che.net.DataWriterAdapter;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.InputStreamDataWriter;
import org.dcm4che.net.PDVInputStream;
import org.dcm4che.net.Status;
import org.dcm4che.net.pdu.AAbort;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.net.service.BasicCStoreSCP;
import org.dcm4che.net.service.DicomServiceException;
import org.dcm4che.net.service.InstanceLocator;
import org.dcm4chee.proxy.mc.net.ForwardTask;
import org.dcm4chee.proxy.mc.net.ProxyApplicationEntity;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class CStoreSCPImpl extends BasicCStoreSCP {

    public CStoreSCPImpl(String... sopClasses) {
        super(sopClasses);
    }

    @Override
    public void onCStoreRQ(Association as, PresentationContext pc, Attributes rq, PDVInputStream data)
            throws IOException {
        Association as2 = (Association) as.getProperty(ProxyApplicationEntity.FORWARD_ASSOCIATION);
        if (as2 == null)
            super.onCStoreRQ(as, pc, rq, data);
        else if (((ProxyApplicationEntity) as.getApplicationEntity()).isCoerceAttributes())
            super.store(as, pc, rq, data, null);
        else
            forward(as, pc, rq, new InputStreamDataWriter(data), as2);
    }

    @Override
    protected File createFile(Association as, Attributes rq, Object storage)
            throws DicomServiceException {
        try {
            ProxyApplicationEntity ae = (ProxyApplicationEntity) as.getApplicationEntity();
            return File.createTempFile("dcm", ".dcm", ae.getSpoolDirectory());
        } catch (Exception e) {
            LOG.warn(as + ": Failed to create temp file:", e);
            throw new DicomServiceException(Status.OutOfResources, e);
        }
    }

    @Override
    protected File process(Association as, PresentationContext pc, Attributes rq, Attributes rsp,
            Object storage, File file, MessageDigest digest) throws IOException {
        Association as2 = (Association) as.getProperty(ProxyApplicationEntity.FORWARD_ASSOCIATION);
        if (as2 == null) {
            scheduleForward(as, pc, rq, file);
            return null;
        }
        Attributes attrs = ((ProxyApplicationEntity) as.getApplicationEntity()).readAndCoerceDataset(file);
        forward(as, pc, rq, new DataWriterAdapter(attrs), as2);
        return file;
    }

    private static void scheduleForward(Association as, PresentationContext pc, Attributes rq,
                File file) {
        ForwardTask forwardTask = (ForwardTask) as.getProperty(ProxyApplicationEntity.FORWARD_TASK);
        String tsuid = pc.getTransferSyntax();
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        forwardTask.add(new InstanceLocator(cuid, iuid, tsuid, file.toURI().toString()));
    }

    private static void forward(final Association as, final PresentationContext pc,
            Attributes rq, DataWriter data, Association as2) throws IOException {
        try {
            String tsuid = pc.getTransferSyntax();
            String cuid = rq.getString(Tag.AffectedSOPClassUID);
            String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
            int priority = rq.getInt(Tag.Priority, 0);
            int msgId = rq.getInt(Tag.MessageID, 0);
            DimseRSPHandler rspHandler = new DimseRSPHandler(msgId) {
                
                @Override
                public void onDimseRSP(Association as2, Attributes cmd, Attributes data) {
                    super.onDimseRSP(as2, cmd, data);
                    try {
                        as.writeDimseRSP(pc, cmd, data);
                    } catch (IOException e) {
                        LOG.warn("Failed to forward C-STORE RSP to " + as, e);
                    }
                }
            };
            as2.cstore(cuid, iuid, priority, data, tsuid, rspHandler);
        } catch (Exception e) {
            LOG.warn("Failed to forward C-STORE RQ to " + as2, e);
            throw new AAbort(AAbort.UL_SERIVE_USER, 0);
        }
    }

}