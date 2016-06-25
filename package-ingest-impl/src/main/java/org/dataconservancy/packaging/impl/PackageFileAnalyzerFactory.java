/*
 * Copyright 2016 Johns Hopkins University
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
package org.dataconservancy.packaging.impl;

import java.io.File;

import org.dataconservancy.packaging.ingest.LdpPackageAnalyzer;
import org.dataconservancy.packaging.ingest.LdpPackageAnalyzerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

@Designate(ocd = PackageFileAnalyzerFactoryConfig.class)
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
public class PackageFileAnalyzerFactory
        implements LdpPackageAnalyzerFactory<File> {

    private File extractBaseDir;

    @Activate
    @Modified
    public void init(PackageFileAnalyzerFactoryConfig config) {
        extractBaseDir = new File(config.package_extract_dir());
        extractBaseDir.mkdirs();
    }

    @Override
    public LdpPackageAnalyzer<File> newAnalyzer() {
        return new PackageFileAnalyzer(new OpenPackageService(),
                                       extractBaseDir);
    }

}