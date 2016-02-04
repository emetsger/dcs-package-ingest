
package org.dataconservancy.packaging.ingest.camel.impl;

import java.io.File;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

import org.dataconservancy.packaging.ingest.camel.DepositWorkflow;
import org.dataconservancy.packaging.ingest.camel.impl.config.PackageFileDepositWorkflowConfig;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.metatype.annotations.Designate;

import static org.dataconservancy.packaging.ingest.camel.NotificationDriver.ROUTE_NOTIFICATION_FAIL;
import static org.dataconservancy.packaging.ingest.camel.NotificationDriver.ROUTE_NOTIFICATION_SUCCESS;
import static org.dataconservancy.packaging.ingest.camel.DepositDriver.ROUTE_DEPOSIT_PROVENANCE;
import static org.dataconservancy.packaging.ingest.camel.DepositDriver.ROUTE_DEPOSIT_RESOURCES;
import static org.dataconservancy.packaging.ingest.camel.DepositDriver.ROUTE_TRANSACTION_BEGIN;
import static org.dataconservancy.packaging.ingest.camel.DepositDriver.ROUTE_TRANSACTION_COMMIT;
import static org.dataconservancy.packaging.ingest.camel.DepositDriver.ROUTE_TRANSACTION_ROLLBACK;

/**
 * The main package deposit workflow.
 * 
 * @author apb@jhu.edu
 */
@Component(service = DepositWorkflow.class, configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = PackageFileDepositWorkflowConfig.class, factory = true)
public class PackageFileDepositWorkflow
        extends RouteBuilder
        implements DepositWorkflow {

    private PackageFileDepositWorkflowConfig config;

    @Activate
    public void init(PackageFileDepositWorkflowConfig config) {
        this.config = config;

        if (config.create_directories()) {
            new File(config.package_deposit_dir()).mkdirs();
            new File(config.package_fail_dir()).mkdirs();
        }
    }

    @Override
    public void configure() throws Exception {

        /* Construct a camel endpoint URI for polling a specific file */
        String fileSourceURI =
                String.format("file:%s?delete=true&readLock=changed&readLockCheckInterval=%d&readLockTimeout=600000",
                              config.package_deposit_dir(),
                              config.package_poll_interval_ms());

        /* Poll the file */
        from(fileSourceURI).id("deposit-poll-file").to("direct:deposit");

        /* Main deposit workflow */
        from("direct:deposit").id("deposit-workflow")
                .setHeader(Exchange.HTTP_URI,
                           constant(config.deposit_location()))
                .doTry().to(ROUTE_TRANSACTION_BEGIN)
                /* .to(ROUTE_DEPOSIT_PROVENANCE) */
                .to(ROUTE_DEPOSIT_RESOURCES).to(ROUTE_TRANSACTION_COMMIT)
                .to(ROUTE_NOTIFICATION_SUCCESS).doCatch(Exception.class)
                .to("direct:fail_copy_package").doTry()
                .to(ROUTE_TRANSACTION_ROLLBACK).doCatch(Exception.class)
                .end()
                .to(ROUTE_NOTIFICATION_FAIL).end();

        /* Copy package to failure directory */
        from("direct:fail_copy_package").id("deposit-fail")
                .to(String.format(
                                  "file:%s?autoCreate=true&keepLastModified=true",
                                  config.package_fail_dir()));
    }
}
