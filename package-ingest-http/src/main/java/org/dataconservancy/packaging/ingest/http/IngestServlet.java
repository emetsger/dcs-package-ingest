/*
 * Copyright 2017 Johns Hopkins University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dataconservancy.packaging.ingest.http;

import static javax.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dataconservancy.packaging.ingest.DepositBuilder;
import org.dataconservancy.packaging.ingest.EventType;
import org.dataconservancy.packaging.ingest.PackageDepositManager;

import org.apache.commons.io.IOUtils;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author apb@jhu.edu
 */
@SuppressWarnings("serial")
@WebServlet(asyncSupported = true, name = "PackageIngest", urlPatterns = { "/ingest" })
@Component(service = HttpServlet.class, property = { "osgi.http.whiteboard.servlet.pattern=/ingest" })
public class IngestServlet extends HttpServlet {

    static final Logger LOG = LoggerFactory.getLogger(IngestServlet.class);

    ExecutorService exe = Executors.newCachedThreadPool();

    PackageDepositManager depositManager;

    /**
     * Set the package deposit manager.
     *
     * @param mgr deposit manager impl.
     */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    public void setPackageDepositManager(final PackageDepositManager mgr) {
        this.depositManager = mgr;
    }

    /** No arg constructor */
    public IngestServlet() {
    }

    /** Initialize with a specific package deposit manager */
    public IngestServlet(final PackageDepositManager manager) {
        this.depositManager = manager;
    }

    @Override
    protected void doOptions(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException {

        LOG.debug("Servicing OPTIONS " + req.getPathInfo());

        printHeaders(req);

        resp.setStatus(SC_OK);
        resp.setHeader("Content-Type", "text/turtle");
        resp.setHeader("Accept-Post", "application/zip,application/x-tgz,application/tar,application/gzip");
        resp.setHeader("Allow", "POST,HEAD,GET,OPTIONS");

        try (OutputStream out = resp.getOutputStream()) {
            IOUtils.copy(this.getClass().getResourceAsStream("/options.ttl"), out);
        }
    }

    @Override
    public void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException,
            IOException {

        LOG.debug("Servicing GET " + req.getPathInfo());

        printHeaders(req);

        resp.setStatus(SC_OK);
        resp.setHeader("Content-Type", "text/turtle");

        try (OutputStream out = resp.getOutputStream();
                InputStream options = this.getClass().getResourceAsStream("/options.ttl")) {
            IOUtils.copy(options, out);
        }
    }

    private void printHeaders(final HttpServletRequest req) {
        final Enumeration<String> headers = req.getHeaderNames();
        while (headers.hasMoreElements()) {
            final String key = headers.nextElement();
            LOG.debug("{} : {}", key, req.getHeader(key));
        }
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException {

        LOG.debug("Servicing POST " + req.getPathInfo());
        printHeaders(req);

        final AsyncContext cxt = req.startAsync();
        cxt.setTimeout(0);

        final DepositBuilder deposit = depositManager.newDeposit()
                .withPackage(cxt.getRequest().getInputStream())
                .intoContainer(uriFromRequest(req));

        exe.execute(() -> {
            try {
                execDeposit(deposit, cxt);
            } catch (final Throwable e) {
                LOG.info("Terminated response with exception", e);
                try {
                    cxt.complete();
                } catch (final Throwable x) {
                    LOG.warn("Error handler could not complete", e);
                }
            }
        });

    }

    private static void execDeposit(final DepositBuilder deposit, final AsyncContext cxt) {
        final HttpServletResponse response = response(cxt);
        final PrintWriter out;
        try {
            out = response.getWriter();
        } catch (final IOException e) {
            LOG.warn("Could not open response writer", e);
            response.setStatus(SC_INTERNAL_SERVER_ERROR);
            return;
        }

        response.setStatus(SC_ACCEPTED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");

        deposit.withListener((event, uri, resource, detail) -> {

            // If the first event we encounter is an error, just throw an http error
            if (EventType.ERROR.equals(event) && !response.isCommitted()) {
                try {
                    LOG.warn("Error thrown in deposit", (Throwable) detail);
                    response.sendError(SC_BAD_REQUEST, "event: error\ndata: " + detail + "\n");
                } catch (final IOException e) {
                    throw new RuntimeException("Could not send error response", e);
                }
                return;
            }

            // Write the event to the stream
            synchronized (cxt) {
                switch (event) {
                case HEARTBEAT:
                    if (response.isCommitted()) {
                        out.println(":");
                    } else {
                        return;
                    }
                    break;
                default:
                    out.println("event: " + event.toString());
                    if (detail != null) {
                        for (final String line : detail.toString().split("\n")) {
                            out.println("data: " + line);
                        }
                    }
                    out.println();
                }
                out.flush();
                flushResponse(response);
            }
        }).perform();

        cxt.complete();
    }

    private static void flushResponse(final HttpServletResponse response) {
        try {
            response.flushBuffer();
        } catch (final IOException e) {
            LOG.warn("Could not flush response", e);
        }
    }

    private static HttpServletResponse response(final AsyncContext cxt) {
        return (HttpServletResponse) cxt.getResponse();
    }

    private static URI uriFromRequest(final HttpServletRequest req) {
        if (req.getHeader("Apix-Resource") != null) {
            LOG.debug("Got container {} fom http header", req.getHeader("Apix-Ldp-Resource"));
            return URI.create(req.getHeader("Apix-Ldp-Resource"));
        } else if (req.getParameter("container") != null) {
            LOG.debug("Got container {} from parameter", req.getParameter("container"));
            return URI.create(req.getParameter("container"));
        } else {
            LOG.info("No container to deposit into specified");
            return null;
        }
    }

}
