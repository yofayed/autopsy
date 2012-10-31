/*
 * Autopsy Forensic Browser
 * 
 * Copyright 2011 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
package org.sleuthkit.autopsy.directorytree;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.beans.PropertyVetoException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import org.sleuthkit.autopsy.coreutils.Logger;
import javax.swing.AbstractAction;
import org.openide.explorer.ExplorerManager;
import org.openide.explorer.view.TreeView;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.sleuthkit.autopsy.corecomponents.DataResultTopComponent;
import org.sleuthkit.autopsy.datamodel.AbstractFsContentNode;
import org.sleuthkit.autopsy.datamodel.BlackboardArtifactNode;
import org.sleuthkit.autopsy.datamodel.ImagesNode;
import org.sleuthkit.autopsy.datamodel.RootContentChildren;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentVisitor;
import org.sleuthkit.datamodel.Directory;
import org.sleuthkit.datamodel.File;
import org.sleuthkit.datamodel.FileSystem;
import org.sleuthkit.datamodel.Image;
import org.sleuthkit.datamodel.LayoutDirectory;
import org.sleuthkit.datamodel.LayoutFile;
import org.sleuthkit.datamodel.TskException;
import org.sleuthkit.datamodel.Volume;
import org.sleuthkit.datamodel.VolumeSystem;

/**
 * View the directory content associated with the given Artifact
 */
class ViewContextAction extends AbstractAction {

    private Content content;
    private static final Logger logger = Logger.getLogger(ViewContextAction.class.getName());

    public ViewContextAction(String title, BlackboardArtifactNode node) {
        super(title);
        this.content = node.getLookup().lookup(Content.class);
      
    }
    
    public ViewContextAction(String title, AbstractFsContentNode node) {
        super(title);
        this.content = node.getLookup().lookup(Content.class);
    }
    
    public ViewContextAction(String title, Content content) {
        super(title);
        this.content = content;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        EventQueue.invokeLater(new Runnable() {

            @Override
            public void run() {
                ReverseHierarchyVisitor vtor = new ReverseHierarchyVisitor();
                List<Content> hierarchy = content.accept(vtor);
                Collections.reverse(hierarchy);
                Node generated = new DirectoryTreeFilterNode(new AbstractNode(new RootContentChildren(hierarchy)), true);
                Children genChilds = generated.getChildren();

                final DirectoryTreeTopComponent directoryTree = DirectoryTreeTopComponent.findInstance();
                TreeView tree = directoryTree.getTree();
                ExplorerManager man = directoryTree.getExplorerManager();
                Node dirRoot = man.getRootContext();
                Children dirChilds = dirRoot.getChildren();
                Node imagesRoot = dirChilds.findChild(ImagesNode.NAME);
                dirChilds = imagesRoot.getChildren();

                Node dirExplored = null;

                for (int i = 0; i < genChilds.getNodesCount() - 1; i++) {
                    Node currentGeneratedNode = genChilds.getNodeAt(i);
                    for (int j = 0; j < dirChilds.getNodesCount(); j++) {
                        Node currentDirectoryTreeNode = dirChilds.getNodeAt(j);
                        if (currentGeneratedNode.getDisplayName().equals(currentDirectoryTreeNode.getDisplayName())) {
                            dirExplored = currentDirectoryTreeNode;
                            tree.expandNode(dirExplored);
                            dirChilds = currentDirectoryTreeNode.getChildren();
                            break;
                        }
                    }
                }

                try {
                    if (dirExplored != null) {
                        tree.expandNode(dirExplored);
                        man.setExploredContextAndSelection(dirExplored, new Node[]{dirExplored});
                    }

                } catch (PropertyVetoException ex) {
                    logger.log(Level.WARNING, "Couldn't set selected node", ex);
                }

                // Another thread is needed because we have to wait for dataResult to populate
                EventQueue.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        DataResultTopComponent dataResult = directoryTree.getDirectoryListing();
                        Node resultRoot = dataResult.getRootNode();
                        Children resultChilds = resultRoot.getChildren();
                        Node generated = content.accept(new RootContentChildren.CreateSleuthkitNodeVisitor());
                        for (int i = 0; i < resultChilds.getNodesCount(); i++) {
                            Node current = resultChilds.getNodeAt(i);
                            if (generated.getName().equals(current.getName())) {
                                dataResult.requestActive();
                                dataResult.setSelectedNodes(new Node[]{current});
                                DirectoryTreeTopComponent.getDefault().fireViewerComplete();
                                break;
                            }
                        }
                    }
                });
            }
        });
    }

    private class ReverseHierarchyVisitor implements ContentVisitor<List<Content>> {

        List<Content> ret = new ArrayList<Content>();

        @Override
        public List<Content> visit(Directory drctr) {
            ret.add(drctr);
            if (drctr.isRoot()) {
                return visit(drctr.getFileSystem());
            } else {
                try {
                    return visit(drctr.getParentDirectory());
                } catch (TskException ex) {
                    logger.log(Level.WARNING, "Couldn't get directory's parent directory", ex);
                }
            }
            return ret;
        }

        @Override
        public List<Content> visit(File file) {
            ret.add(file);
            if (file.isRoot()) {
                return visit(file.getFileSystem());
            } else {
                try {
                    return visit(file.getParentDirectory());
                } catch (TskException ex) {
                    logger.log(Level.WARNING, "Couldn't get file's parent directory", ex);
                }
            }
            return ret;
        }

        @Override
        public List<Content> visit(FileSystem fs) {
            return fs.getParent().accept(this);
        }

        @Override
        public List<Content> visit(Image image) {
            ret.add(image);
            return ret;
        }

        @Override
        public List<Content> visit(Volume volume) {
            ret.add(volume);
            return visit(volume.getParent());
        }

        @Override
        public List<Content> visit(VolumeSystem vs) {
            return visit(vs.getParent());
        }
        
        @Override
        public List<Content> visit(LayoutFile lc) {
            ret.add(lc);
            ret.addAll(lc.getParent().accept(this));
              
            
            return ret;

        }
        
        @Override
        public List<Content> visit(LayoutDirectory ld) {
             ret.add(ld);
             ret.addAll(ld.getParent().accept(this));
          
            return ret;
        }
    }
}
