package com.comfyui.cache.ui;

import com.comfyui.cache.CacheDatabase;
import com.comfyui.cache.CacheEngine;
import com.comfyui.cache.CacheEntry;
import com.comfyui.cache.CacheException;
import com.comfyui.cache.CacheSource;
import com.comfyui.cache.EntryStatus;
import com.comfyui.cache.FileUtil;
import com.comfyui.cache.LinkSupport;
import com.comfyui.cache.ProgressSink;
import com.comfyui.cache.ScanNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** 主窗口。 */
public final class MainFrame extends JFrame {

    private static final String TITLE = "ComfyUI 模型缓存";

    private final CacheDatabase db;
    private final CacheEngine engine;
    private final BrowserPanel browser;
    private final DetailPanel detail;
    private final CacheTablePanel cacheTable;

    private final JComboBox<CacheSource> sourceCombo = new JComboBox<>();
    private final JLabel cacheDirLabel = Theme.muted("（未设置）");
    private final JLabel statusLabel = Theme.muted("就绪");
    private final JLabel dbLabel = Theme.muted("");

    private final JPanel banner = new JPanel(new BorderLayout(10, 0));
    private final JLabel bannerLabel = new JLabel();
    private final JPanel bannerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    private CacheSource currentSource;
    private Map<String, EntryStatus> statuses = new LinkedHashMap<>();
    private LinkSupport.Report linkReport;
    private boolean busy;

    public MainFrame(CacheDatabase db) {
        super(TITLE);
        this.db = db;
        this.engine = new CacheEngine(db);
        this.browser = new BrowserPanel(this::onTreeSelection);
        this.detail = new DetailPanel(new DetailPanel.Listener() {
            @Override
            public void onCacheRequested(ScanNode node) {
                requestCache(node);
            }

            @Override
            public void onUncacheRequested(ScanNode node) {
                requestUncacheNode(node);
            }
        });
        this.cacheTable = new CacheTablePanel(new CacheTablePanel.Listener() {
            @Override
            public void onUncache(List<CacheTablePanel.Row> rows) {
                requestUncacheRows(rows);
            }

            @Override
            public void onVerifyAll() {
                refreshAll();
            }

            @Override
            public void onReveal(CacheTablePanel.Row row) {
                try {
                    Openers.reveal(engine.cachePathFor(row.entry().rel()));
                } catch (CacheException e) {
                    Dialogs.warn(MainFrame.this, TITLE, e.getMessage());
                }
            }
        });

        setIconImages(List.of(Icons.image(16), Icons.image(24), Icons.image(32), Icons.image(48),
                Icons.image(64), Icons.image(128)));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (busy) {
                    Dialogs.warn(MainFrame.this, TITLE, "还有操作正在进行，请等它结束再关闭窗口。");
                    return;
                }
                dispose();
                System.exit(0);
            }
        });

        buildUi();
    }

    // ------------------------------------------------------------- 启动

    public void start() {
        applyScreenBounds();
        browser.setOnlyModelFiles(db.onlyModelFiles());
        reloadSources(null);
        setVisible(true);
        probeLinks();
        if (db.loadWarning() != null) {
            Dialogs.warn(this, TITLE, db.loadWarning());
        }
        if (currentSource == null) {
            browser.setData(null, Map.of(), null);
            browser.setEmptyHint("点上面「添加模型目录」选择 ComfyUI 的 models 文件夹");
            detail.showEmpty("先用「添加模型目录」选中 ComfyUI 的 models 文件夹，"
                    + "再用「选择缓存目录」指定固态硬盘上的缓存位置。");
            setStatus("还没有添加模型目录");
        } else {
            refreshAll();
        }
        if (db.cacheDir() == null) {
            setStatus("还没有设置缓存目录，点工具栏的「选择缓存目录」");
        }
    }

    /**
     * 高分屏上窗口容易有一半跑到屏幕外面，原因是：
     * {@code getMaximumWindowBounds()} 返回的是设备像素，而 {@code setSize/setLocation}
     * 用的是逻辑像素（Windows 150% 缩放时两者差 1.5 倍）。这里按缩放比例换算后再定位。
     */
    private void applyScreenBounds() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) {
            configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        AffineTransform transform = configuration.getDefaultTransform();
        double scaleX = transform.getScaleX() > 0 ? transform.getScaleX() : 1.0;
        double scaleY = transform.getScaleY() > 0 ? transform.getScaleY() : 1.0;

        Rectangle deviceBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int usableX = (int) Math.round(deviceBounds.x / scaleX);
        int usableY = (int) Math.round(deviceBounds.y / scaleY);
        int usableWidth = (int) Math.floor(deviceBounds.width / scaleX);
        int usableHeight = (int) Math.floor(deviceBounds.height / scaleY);

        int width = Math.max(720, Math.min(1240, usableWidth - 40));
        int height = Math.max(480, Math.min(800, usableHeight - 40));
        setMinimumSize(new Dimension(Math.min(960, width), Math.min(600, height)));
        setSize(width, height);
        setLocation(usableX + Math.max(0, (usableWidth - width) / 2),
                usableY + Math.max(0, (usableHeight - height) / 2));
    }

    // ------------------------------------------------------------- 界面搭建

    private void buildUi() {
        setLayout(new BorderLayout());
        setJMenuBar(buildMenuBar());

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildBanner());
        top.add(buildToolbar());
        add(top, BorderLayout.NORTH);

        JSplitPane upper = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browser, detail);
        upper.setResizeWeight(1.0);
        upper.setDividerSize(6);
        upper.setBorder(null);
        upper.setContinuousLayout(true);

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, cacheTable);
        center.setResizeWeight(0.64);
        center.setDividerSize(6);
        center.setBorder(null);
        center.setContinuousLayout(true);
        add(center, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("文件");
        file.setFont(Theme.FONT);
        JMenuItem addDir = new JMenuItem("添加模型目录…");
        addDir.addActionListener(e -> addModelsDir());
        JMenuItem pickCache = new JMenuItem("选择缓存目录…");
        pickCache.addActionListener(e -> chooseCacheDir());
        JMenuItem openDb = new JMenuItem("打开数据文件位置");
        openDb.addActionListener(e -> Openers.reveal(db.file()));
        JMenuItem exit = new JMenuItem("退出");
        exit.addActionListener(e -> {
            dispose();
            System.exit(0);
        });
        file.add(addDir);
        file.add(pickCache);
        file.addSeparator();
        file.add(openDb);
        file.addSeparator();
        file.add(exit);

        JMenu tools = new JMenu("工具");
        tools.setFont(Theme.FONT);
        JMenuItem rescan = new JMenuItem("重新扫描并检查");
        rescan.addActionListener(e -> refreshAll());
        JMenuItem diagnose = new JMenuItem("链接能力诊断…");
        diagnose.addActionListener(e -> showDiagnostics());
        JMenuItem devMode = new JMenuItem("开启 Windows 开发者人员模式…");
        devMode.addActionListener(e -> enableDeveloperMode());
        JMenuItem elevate = new JMenuItem("以管理员身份重新启动…");
        elevate.addActionListener(e -> relaunchAsAdmin());
        tools.add(rescan);
        tools.addSeparator();
        tools.add(diagnose);
        tools.add(devMode);
        tools.add(elevate);

        JMenu help = new JMenu("帮助");
        help.setFont(Theme.FONT);
        JMenuItem usage = new JMenuItem("使用说明");
        usage.addActionListener(e -> showHelp());
        JMenuItem about = new JMenuItem("关于");
        about.addActionListener(e -> Dialogs.info(this, "关于", aboutText()));
        help.add(usage);
        help.add(about);

        bar.add(file);
        bar.add(tools);
        bar.add(help);
        return bar;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
                Theme.padded(8, 12, 8, 12)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(Theme.muted("模型目录"));

        sourceCombo.setFont(Theme.FONT);
        sourceCombo.setPreferredSize(new Dimension(330, 30));
        sourceCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof CacheSource source) {
                    setText(source.displayName() + "    " + elide(source.modelsDir(), 46));
                    setToolTipText(source.modelsDir());
                } else {
                    setText("（还没有添加模型目录）");
                }
                return this;
            }
        });
        sourceCombo.addActionListener(e -> {
            Object selected = sourceCombo.getSelectedItem();
            if (selected instanceof CacheSource source && source != currentSource) {
                currentSource = source;
                refreshAll();
            }
        });
        left.add(sourceCombo);

        JButton add = Theme.iconButton(Icons.of(Icons.PLUS, Theme.MUTED), "添加模型目录");
        add.addActionListener(e -> addModelsDir());
        JButton remove = Theme.iconButton(Icons.of(Icons.MINUS, Theme.MUTED), "移除选中的模型目录");
        remove.addActionListener(e -> removeSource());
        left.add(add);
        left.add(remove);
        left.add((JComponent) Box.createHorizontalStrut(12));

        left.add(Theme.muted("缓存目录"));
        cacheDirLabel.setFont(Theme.FONT);
        cacheDirLabel.setForeground(Theme.TEXT);
        left.add(cacheDirLabel);
        JButton changeCache = Theme.button("选择缓存目录", Icons.of(Icons.DISK, Theme.MUTED));
        changeCache.addActionListener(e -> chooseCacheDir());
        left.add(changeCache);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton refresh = Theme.button("刷新", Icons.of(Icons.REFRESH, Theme.MUTED));
        refresh.addActionListener(e -> refreshAll());
        right.add(refresh);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildBanner() {
        banner.setBackground(Theme.WARN_SOFT);
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xEFD9AE)),
                Theme.padded(9, 12, 9, 12)));
        bannerLabel.setFont(Theme.FONT);
        bannerLabel.setForeground(Theme.WARN);
        bannerLabel.setIcon(Icons.of(Icons.WARN, Theme.WARN));
        bannerLabel.setIconTextGap(8);
        bannerActions.setOpaque(false);
        banner.add(bannerLabel, BorderLayout.CENTER);
        banner.add(bannerActions, BorderLayout.EAST);
        banner.setVisible(false);
        return banner;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(Theme.BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE),
                Theme.padded(5, 12, 5, 12)));
        bar.add(statusLabel, BorderLayout.WEST);

        dbLabel.setText("数据文件：" + db.file().getFileName());
        dbLabel.setToolTipText(db.file().toString());
        dbLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dbLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Openers.reveal(db.file());
            }
        });
        bar.add(dbLabel, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------- 基本操作

    private void reloadSources(CacheSource select) {
        sourceCombo.removeAllItems();
        for (CacheSource source : db.sources()) {
            sourceCombo.addItem(source);
        }
        if (select != null) {
            sourceCombo.setSelectedItem(select);
        }
        Object item = sourceCombo.getSelectedItem();
        currentSource = item instanceof CacheSource source ? source : null;

        Path cacheDir = db.cacheDir();
        cacheDirLabel.setText(cacheDir == null ? "（未设置）" : elide(cacheDir.toString(), 42));
        cacheDirLabel.setToolTipText(cacheDir == null ? null : cacheDir.toString());
    }

    private void addModelsDir() {
        JFileChooser chooser = chooserFor(currentSource == null ? null : currentSource.modelsDirPath());
        chooser.setDialogTitle("选择 ComfyUI 的模型目录（models 文件夹）");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path dir = chooser.getSelectedFile().toPath();
        if (!Files.isDirectory(dir)) {
            Dialogs.warn(this, TITLE, "请选择一个文件夹。");
            return;
        }
        Path cacheDir = db.cacheDir();
        if (cacheDir != null && (dir.startsWith(cacheDir) || cacheDir.startsWith(dir))) {
            Dialogs.warn(this, TITLE, "这个目录和缓存目录互相包含，请换一个。");
            return;
        }
        CacheSource source = db.addSource(dir);
        if (!saveDatabase()) {
            return;
        }
        reloadSources(source);
        refreshAll();
    }

    private void removeSource() {
        if (currentSource == null) {
            Dialogs.info(this, TITLE, "还没有添加模型目录。");
            return;
        }
        if (!currentSource.entries().isEmpty()) {
            Dialogs.warn(this, TITLE, "这个目录里还有 " + currentSource.entries().size()
                    + " 项缓存记录。\n请先取消缓存再移除这个目录。");
            return;
        }
        if (!Dialogs.confirm(this, TITLE, "移除模型目录？\n\n" + currentSource.modelsDir()
                + "\n\n只会把它从列表里移除，不会动磁盘上的任何文件。", "移除")) {
            return;
        }
        db.removeSource(currentSource);
        saveDatabase();
        currentSource = null;
        reloadSources(null);
        if (currentSource == null) {
            browser.setData(null, Map.of(), null);
            browser.setEmptyHint("点「+」添加 ComfyUI 的 models 文件夹");
            detail.showEmpty("还没有模型目录。");
            cacheTable.setRows(buildRows());
            setStatus("还没有添加模型目录");
        } else {
            refreshAll();
        }
    }

    private void chooseCacheDir() {
        JFileChooser chooser = chooserFor(db.cacheDir());
        chooser.setDialogTitle("选择缓存目录（建议放在固态硬盘上）");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path dir = chooser.getSelectedFile().toPath();
        for (CacheSource source : db.sources()) {
            if (dir.startsWith(source.modelsDirPath()) || source.modelsDirPath().startsWith(dir)) {
                Dialogs.warn(this, TITLE, "缓存目录不能和模型目录互相包含：\n" + source.modelsDirPath());
                return;
            }
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Dialogs.error(this, TITLE, "这个目录不可用：" + e.getMessage());
            return;
        }
        db.setCacheDir(dir);
        saveDatabase();
        reloadSources(currentSource);
        probeLinks();
        refreshAll();
    }

    private void probeLinks() {
        Path probeDir = db.cacheDir() != null ? db.cacheDir() : db.folder();
        new SwingWorker<LinkSupport.Report, Void>() {
            @Override
            protected LinkSupport.Report doInBackground() {
                return LinkSupport.probe(probeDir);
            }

            @Override
            protected void done() {
                try {
                    linkReport = get();
                } catch (Exception ignored) {
                    linkReport = null;
                }
                updateBanner();
            }
        }.execute();
    }

    private void updateBanner() {
        bannerActions.removeAll();
        if (db.cacheDir() == null) {
            bannerLabel.setText("还没有设置缓存目录。固态硬盘上的任意文件夹都可以。");
            bannerLabel.setToolTipText("缓存目录要放在固态硬盘上，例如 C:\\Users\\你的用户名\\modelcache。");
            JButton pick = Theme.button("选择缓存目录", Icons.of(Icons.DISK, Theme.MUTED));
            pick.addActionListener(e -> chooseCacheDir());
            bannerActions.add(pick);
            banner.setVisible(true);
            revalidate();
            return;
        }
        if (linkReport != null && !linkReport.fileSymlink() && LinkSupport.IS_WINDOWS) {
            bannerLabel.setText("当前系统不允许创建符号链接，可以开启开发者人员模式，或改用免权限的「目录模式」。");
            bannerLabel.setToolTipText("Windows 普通用户默认不能创建符号链接。"
                    + "点「开启开发者人员模式」可以解决；或者用免权限的「目录模式」："
                    + "在左侧选中一个文件夹再点缓存，程序会把整个文件夹搬到缓存盘并建立目录联接。");
            JButton fix = Theme.button("开启开发者人员模式", Icons.of(Icons.CHECK, Theme.MUTED));
            fix.addActionListener(e -> enableDeveloperMode());
            JButton elevation = Theme.button("以管理员身份重启", Icons.of(Icons.REFRESH, Theme.MUTED));
            elevation.addActionListener(e -> relaunchAsAdmin());
            JButton more = Theme.button("详情", Icons.of(Icons.INFO, Theme.MUTED));
            more.addActionListener(e -> showDiagnostics());
            bannerActions.add(fix);
            bannerActions.add(elevation);
            bannerActions.add(more);
            banner.setVisible(true);
            revalidate();
            return;
        }
        banner.setVisible(false);
        bannerLabel.setToolTipText(null);
        revalidate();
    }

    private void refreshAll() {
        if (busy) {
            return;
        }
        if (currentSource == null) {
            browser.setData(null, Map.of(), null);
            cacheTable.setRows(buildRows());
            return;
        }
        final CacheSource source = currentSource;
        busy = true;
        browser.setBusy(true, "正在扫描模型目录…");
        setStatus("正在扫描模型目录…");
        new SwingWorker<RefreshResult, Void>() {
            @Override
            protected RefreshResult doInBackground() {
                try {
                    ScanNode root = engine.scan(source.modelsDirPath(), ProgressSink.NOOP);
                    Map<String, EntryStatus> map = new LinkedHashMap<>();
                    for (CacheEntry entry : source.entries()) {
                        map.put(CacheSource.key(entry.rel()), engine.verify(source, entry));
                    }
                    return new RefreshResult(root, map, buildRows(), null);
                } catch (Exception e) {
                    return new RefreshResult(null, new LinkedHashMap<>(), new ArrayList<>(), e);
                }
            }

            @Override
            protected void done() {
                busy = false;
                browser.setBusy(false, null);
                RefreshResult result;
                try {
                    result = get();
                } catch (Exception e) {
                    handleError("扫描失败", e);
                    return;
                }
                if (result.error() != null) {
                    handleError("扫描失败", result.error());
                    return;
                }
                statuses = result.statuses();
                browser.setData(result.root(), result.statuses(), source.modelsDirPath());
                cacheTable.setRows(result.rows());
                List<ScanNode> files = new ArrayList<>();
                result.root().collectFiles(files);
                setStatus("共 " + files.size() + " 个文件 · 已缓存 " + db.entryCount() + " 项");
                updateDetail();
            }
        }.execute();
    }

    private List<CacheTablePanel.Row> buildRows() {
        List<CacheTablePanel.Row> rows = new ArrayList<>();
        for (CacheSource source : db.sources()) {
            for (CacheEntry entry : source.entries()) {
                rows.add(new CacheTablePanel.Row(source, entry, engine.verify(source, entry)));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------- 缓存操作

    private void requestCache(ScanNode node) {
        if (node == null) {
            Dialogs.info(this, TITLE, "请先在左侧选择一个模型文件或目录。");
            return;
        }
        if (currentSource == null) {
            Dialogs.warn(this, TITLE, "请先添加模型目录。");
            return;
        }
        if (db.cacheDir() == null) {
            Dialogs.warn(this, TITLE, "请先设置缓存目录。");
            chooseCacheDir();
            return;
        }
        CacheEntry ancestor = currentSource.cachedAncestorOf(node.rel);
        if (ancestor != null) {
            Dialogs.info(this, TITLE, "上级目录「" + ancestor.rel() + "」已经整体缓存，"
                    + "这个文件已经在缓存盘上了。");
            return;
        }
        if (currentSource.find(node.rel) != null) {
            Dialogs.info(this, TITLE, "这一项已经缓存过了。");
            return;
        }
        if (!node.directory) {
            long free = FileUtil.usableSpace(db.cacheDir());
            String question = "把这个模型缓存到高速盘？\n\n"
                    + "文件：" + node.rel + "\n"
                    + "大小：" + FileUtil.humanSize(node.size) + "\n"
                    + "缓存盘可用：" + (free < 0 ? "未知" : FileUtil.humanSize(free)) + "\n\n"
                    + "缓存后：\n"
                    + "· 文件复制到缓存目录（相对路径不变）\n"
                    + "· 原文件改名为 " + node.name + "# 作为备份，ComfyUI 不会再加载它\n"
                    + "· 原位置建立符号链接指回缓存文件\n\n"
                    + "原文件会保留，所以需要两份磁盘空间。";
            if (!Dialogs.confirm(this, "缓存到高速盘", question, "开始缓存")) {
                return;
            }
            startCacheTask(node);
            return;
        }

        runBackground("正在统计目录大小…", () -> {
            Path path = CacheEngine.modelPathFor(currentSource, node.rel);
            return new long[]{FileUtil.treeSize(path), FileUtil.countFiles(path),
                    FileUtil.usableSpace(db.cacheDir())};
        }, info -> {
            String question = "整体缓存这个目录？\n\n"
                    + "目录：" + node.rel + "\n"
                    + "文件数：" + info[1] + "，共 " + FileUtil.humanSize(info[0]) + "\n"
                    + "缓存盘可用：" + (info[2] < 0 ? "未知" : FileUtil.humanSize(info[2])) + "\n\n"
                    + "缓存后：\n"
                    + "· 目录内容移动到缓存盘\n"
                    + "· 原位置变成" + (LinkSupport.IS_WINDOWS ? "目录联接" : "符号链接")
                    + "（Windows 上不需要管理员权限）\n"
                    + "· ComfyUI 仍然按原来的路径读取\n\n"
                    + "注意：这个目录下的所有模型都会放到缓存盘上。";
            if (!Dialogs.confirm(this, "缓存整个目录", question, "开始缓存")) {
                return;
            }
            startCacheTask(node);
        });
    }

    private void startCacheTask(ScanNode node) {
        final CacheSource source = currentSource;
        TaskDialog.run(this, "正在缓存", "正在准备…",
                sink -> node.directory
                        ? engine.cacheDirectory(source, node.rel, sink)
                        : engine.cacheFile(source, node.rel, sink),
                summary -> {
                    refreshAll();
                    Dialogs.info(this, "缓存完成", summary);
                },
                error -> handleError("缓存失败", error));
    }

    private void requestUncacheNode(ScanNode node) {
        if (currentSource == null || node == null) {
            return;
        }
        CacheEntry entry = currentSource.find(node.rel);
        if (entry == null) {
            Dialogs.info(this, TITLE, "这一项不在缓存列表里。");
            return;
        }
        requestUncacheRows(List.of(new CacheTablePanel.Row(currentSource, entry,
                statuses.getOrDefault(CacheSource.key(node.rel), EntryStatus.notCached()))));
    }

    private void requestUncacheRows(List<CacheTablePanel.Row> rows) {
        if (rows == null || rows.isEmpty()) {
            Dialogs.info(this, TITLE, "请先在缓存列表里选择要取消的项目。");
            return;
        }
        long total = 0;
        for (CacheTablePanel.Row row : rows) {
            if (row.entry().size() > 0) {
                total += row.entry().size();
            }
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < rows.size() && i < 12; i++) {
            names.append("\n· ").append(rows.get(i).entry().rel());
        }
        if (rows.size() > 12) {
            names.append("\n… 以及另外 ").append(rows.size() - 12).append(" 项");
        }
        String question = "取消缓存 " + rows.size() + " 项？" + names + "\n\n"
                + "会释放约 " + FileUtil.humanSize(total) + " 缓存盘空间。\n"
                + "步骤：删除原位置的链接 → 把备份改回原来的名字 → 删除缓存盘上的副本。\n"
                + "如果 ComfyUI 正在使用这些模型，请先关闭它。";
        if (!Dialogs.confirm(this, "取消缓存", question, "取消缓存")) {
            return;
        }
        List<CacheTablePanel.Row> copy = new ArrayList<>(rows);
        TaskDialog.run(this, "正在取消缓存", "正在准备…", sink -> {
            StringBuilder report = new StringBuilder();
            int ok = 0;
            List<String> failures = new ArrayList<>();
            for (CacheTablePanel.Row row : copy) {
                sink.message("正在取消缓存：" + row.entry().rel());
                try {
                    engine.uncache(row.source(), row.entry(), sink);
                    ok++;
                } catch (FileUtil.CancelledException cancelled) {
                    throw cancelled;
                } catch (Exception e) {
                    failures.add(row.entry().rel() + "：" + firstLine(e.getMessage()));
                }
            }
            report.append("已取消缓存 ").append(ok).append(" 项。");
            if (!failures.isEmpty()) {
                report.append("\n\n有 ").append(failures.size()).append(" 项没有完成：");
                for (String failure : failures) {
                    report.append("\n· ").append(failure);
                }
            }
            return report.toString();
        }, summary -> {
            refreshAll();
            Dialogs.info(this, "取消缓存", summary);
        }, error -> handleError("取消缓存失败", error));
    }

    // ------------------------------------------------------------- 状态与工具

    private void onTreeSelection(ScanNode node) {
        if (node == null) {
            detail.showEmpty("从左侧选择一个模型文件或目录，然后点「缓存到高速盘」。");
            return;
        }
        if (node.rel.isEmpty()) {
            detail.showEmpty("这是模型目录的根节点。展开它，选中里面的模型或子文件夹，"
                    + "右侧就会出现「缓存到高速盘」按钮。");
            return;
        }
        EntryStatus status = statuses.getOrDefault(CacheSource.key(node.rel), EntryStatus.notCached());
        CacheEntry entry = currentSource == null ? null : currentSource.find(node.rel);
        Path modelPath = null;
        Path cachePath = null;
        Path backupPath = null;
        try {
            if (currentSource != null) {
                modelPath = CacheEngine.modelPathFor(currentSource, node.rel);
            }
            if (db.cacheDir() != null) {
                cachePath = db.cacheDir().resolve(node.rel.replace('/', java.io.File.separatorChar));
            }
            if (entry != null && entry.kind() == CacheEntry.Kind.FILE && modelPath != null) {
                backupPath = modelPath.resolveSibling(modelPath.getFileName() + CacheEntry.BACKUP_SUFFIX);
            }
        } catch (CacheException ignored) {
            // 路径异常时按空处理
        }
        detail.showNode(node, status, entry, modelPath, cachePath, backupPath);
    }

    private void updateDetail() {
        onTreeSelection(browser.getSelectedNode());
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.isEmpty() ? "就绪" : text);
    }

    private <T> void runBackground(String status, Callable<T> work, Consumer<T> onDone) {
        setStatus(status);
        browser.setBusy(true, status);
        busy = true;
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                busy = false;
                browser.setBusy(false, null);
                try {
                    onDone.accept(get());
                } catch (Exception e) {
                    handleError("操作失败", unwrap(e));
                }
            }
        }.execute();
    }

    private void handleError(String title, Throwable error) {
        if (error instanceof FileUtil.CancelledException) {
            setStatus("已取消");
            return;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        setStatus(title + "：" + firstLine(message));
        Dialogs.error(this, TITLE, title + "\n\n" + message);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.ExecutionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index);
    }

    private boolean saveDatabase() {
        try {
            db.save();
            return true;
        } catch (IOException e) {
            Dialogs.error(this, TITLE, "保存数据文件失败：\n" + e.getMessage());
            return false;
        }
    }

    private JFileChooser chooserFor(Path start) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setDialogType(JFileChooser.OPEN_DIALOG);
        if (start != null && Files.isDirectory(start)) {
            chooser.setCurrentDirectory(start.toFile());
        }
        return chooser;
    }

    private static String elide(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int head = Math.max(6, max / 2 - 2);
        int tail = Math.max(6, max - head - 3);
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    // ------------------------------------------------------------- 诊断 / 帮助

    private void showDiagnostics() {
        browser.setBusy(true, "正在检测链接能力…");
        new SwingWorker<LinkSupport.Report, Void>() {
            @Override
            protected LinkSupport.Report doInBackground() {
                Path probeDir = db.cacheDir() != null ? db.cacheDir() : db.folder();
                return LinkSupport.probe(probeDir);
            }

            @Override
            protected void done() {
                browser.setBusy(false, null);
                try {
                    linkReport = get();
                } catch (Exception e) {
                    Dialogs.error(MainFrame.this, TITLE, "检测失败：" + e.getMessage());
                    return;
                }
                updateBanner();
                StringBuilder sb = new StringBuilder();
                sb.append("操作系统：").append(System.getProperty("os.name")).append(' ')
                        .append(System.getProperty("os.version")).append('\n');
                sb.append("Java：").append(System.getProperty("java.version")).append('\n');
                Path cacheDir = db.cacheDir();
                sb.append("缓存目录：").append(cacheDir == null ? "（未设置）" : cacheDir).append('\n');
                sb.append("模型目录：").append(currentSource == null ? "（未添加）" : currentSource.modelsDir())
                        .append('\n');
                sb.append("数据文件：").append(db.file()).append("\n\n");
                sb.append("链接能力：\n");
                sb.append("· 文件符号链接：").append(linkReport.fileSymlink() ? "可用" : "不可用").append('\n');
                sb.append("· 目录符号链接：").append(linkReport.dirSymlink() ? "可用" : "不可用").append('\n');
                if (LinkSupport.IS_WINDOWS) {
                    sb.append("· 目录联接（junction）：").append(linkReport.junction() ? "可用" : "不可用")
                            .append('\n');
                }
                if (!linkReport.fileSymlink() && !linkReport.fileSymlinkError().isEmpty()) {
                    sb.append("· 失败原因：").append(linkReport.fileSymlinkError()).append('\n');
                }
                sb.append('\n').append(advice());
                Dialogs.info(MainFrame.this, "链接能力诊断", sb.toString());
            }
        }.execute();
    }

    private String advice() {
        if (linkReport.fileSymlink()) {
            return "单文件缓存和目录缓存都可以正常使用。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("现在可以直接用「目录模式」：在左侧选中一个文件夹，然后点「缓存到高速盘」，")
                .append("程序会把整个文件夹搬到缓存盘，并在原位置建立目录联接，ComfyUI 照常读取。");
        if (LinkSupport.IS_WINDOWS) {
            sb.append("\n\n想让单文件缓存也能用，二选一：\n")
                    .append("1. 菜单「工具 → 开启 Windows 开发者人员模式」（点一次 UAC 确认，之后普通权限即可）；\n")
                    .append("2. 菜单「工具 → 以管理员身份重新启动」。");
        }
        return sb.toString();
    }

    private void enableDeveloperMode() {
        if (!LinkSupport.IS_WINDOWS) {
            Dialogs.info(this, TITLE, "其它系统创建符号链接不需要额外权限，无需此操作。");
            return;
        }
        if (!Dialogs.confirm(this, "开启开发者人员模式",
                "这一步会让 Windows 允许普通用户创建符号链接：\n\n"
                        + "注册表位置：HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\AppModelUnlock\n"
                        + "值名称：AllowDevelopmentWithoutDevLicense，设为 1\n\n"
                        + "系统会弹出 UAC 授权窗口，点「是」即可。这个开关是 Windows 官方提供的，"
                        + "关掉它也不会影响已经缓存好的文件。\n\n继续吗？", "继续")) {
            return;
        }
        Path statusFile;
        try {
            statusFile = Files.createTempFile("comfyui-cache-devmode-", ".status");
        } catch (IOException e) {
            Dialogs.error(this, TITLE, "无法创建临时文件：" + e.getMessage());
            return;
        }
        runElevatedScript(Elevation.buildRegistryScript(statusFile), statusFile, "开启开发者人员模式", () -> {
            probeLinks();
            Dialogs.info(this, TITLE, "开发者人员模式已开启。\n\n"
                    + "如果顶部的黄条还在，重新启动本程序后符号链接就会生效。");
        });
    }

    private void relaunchAsAdmin() {
        String jar = applicationJar();
        if (jar == null) {
            Dialogs.info(this, TITLE, "没有找到程序自身的 jar 文件，请手动右键以管理员身份运行。");
            return;
        }
        if (!Dialogs.confirm(this, "以管理员身份重新启动",
                "会弹出 UAC 授权窗口，以管理员身份重新启动本程序。\n"
                        + "确认后会关闭当前窗口，并在新窗口里继续。\n\n继续吗？", "重新启动")) {
            return;
        }
        Path jarPath = Path.of(jar);
        Path workingDir = jarPath.getParent() == null ? Path.of(".") : jarPath.getParent();
        Path statusFile;
        try {
            statusFile = Files.createTempFile("comfyui-cache-relaunch-", ".status");
        } catch (IOException e) {
            Dialogs.error(this, TITLE, "无法创建临时文件：" + e.getMessage());
            return;
        }
        String script = Elevation.buildStartScript(javawPath(),
                List.of("-jar", jar, "--db", db.file().toString()), workingDir, statusFile);
        runElevatedScript(script, statusFile, "以管理员身份重新启动", () -> {
            dispose();
            System.exit(0);
        });
    }

    /**
     * 后台线程里执行提权脚本，结束后回到界面线程：成功时执行 onSuccess，
     * 失败时弹窗说明原因（以前失败只会看到窗口一闪，拿不到任何信息）。
     */
    private void runElevatedScript(String script, Path statusFile, String actionName, Runnable onSuccess) {
        setStatus("正在等待管理员授权…（请在弹出的 UAC 窗口里点「是」）");
        new Thread(() -> {
            Elevation.Result result = Elevation.runScript(script, statusFile, 180);
            SwingUtilities.invokeLater(() -> {
                if (result.success()) {
                    setStatus(actionName + "：成功");
                    onSuccess.run();
                } else {
                    setStatus(actionName + "失败");
                    Dialogs.error(this, TITLE, actionName + "失败：\n\n" + result.detail()
                            + "\n\n如果仍然不行，可以右键「启动 ComfyUI模型缓存.bat」→「以管理员身份运行」。");
                }
            });
        }, "elevate").start();
    }

    private static String javawPath() {
        Path home = Path.of(System.getProperty("java.home"));
        Path exe = home.resolve("bin").resolve(LinkSupport.IS_WINDOWS ? "javaw.exe" : "java");
        return Files.isRegularFile(exe) ? exe.toString() : (LinkSupport.IS_WINDOWS ? "javaw.exe" : "java");
    }

    private static String applicationJar() {
        try {
            var source = MainFrame.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void showHelp() {
        Dialogs.info(this, "使用说明", helpText());
    }

    private static String aboutText() {
        return "ComfyUI 模型缓存\n\n"
                + "把不常用的模型留在机械硬盘，把常用的模型搬到固态硬盘，"
                + "再在原位置建立链接，ComfyUI 完全无感。\n\n"
                + "数据文件：" + CacheDatabase.defaultFile() + "\n"
                + "Java " + System.getProperty("java.version");
    }

    private static String helpText() {
        return "【怎么用】\n"
                + "1. 上面的「模型目录」选 ComfyUI 的 models 文件夹，可以添加多个。\n"
                + "   然后点「选择缓存目录」，选固态硬盘上的一个文件夹。\n"
                + "2. 左边选中一个模型文件或文件夹，点右下角的「缓存到高速盘」。\n"
                + "3. 想取消就在下面「缓存列表」里选中，点「取消缓存」。\n"
                + "\n"
                + "【单文件缓存做了什么】\n"
                + "1. 把文件复制到缓存目录，保持相对路径不变；\n"
                + "2. 把原文件改名为「文件名#」作为备份，ComfyUI 不会再加载它；\n"
                + "3. 在原位置创建符号链接，指向缓存盘上的文件。\n"
                + "取消缓存时反过来：删链接 → 把备份改回原名 → 删除缓存盘上的副本"
                + "（目录空了会一起删掉）。\n"
                + "\n"
                + "【为什么有的系统不让建符号链接】\n"
                + "Linux / macOS 上普通用户就能建符号链接。\n"
                + "Windows 出于安全默认只允许管理员建，打开「开发者人员模式」后普通用户也可以。\n"
                + "另外 Windows 的「目录联接」任何用户都能建，所以可以用「目录模式」："
                + "选中一个文件夹再点缓存，程序会把整个文件夹搬到缓存盘并建立目录联接。\n"
                + "菜单「工具 → 链接能力诊断」能看到当前环境支持什么。\n"
                + "\n"
                + "【注意】\n"
                + "· 单文件缓存会保留原文件（改名成 # 结尾），所以需要两份磁盘空间。\n"
                + "· ComfyUI 正在使用某个模型时，Windows 可能不允许改名或删除，请先关闭 ComfyUI。\n"
                + "· 缓存目录不能和模型目录互相包含，否则 ComfyUI 会重复扫描。\n"
                + "· # 结尾的备份文件不会显示在左边的文件树里；选中已缓存的模型，右侧能看到它的位置。\n"
                + "· 所有记录都保存在数据文件里（见窗口右下角），里面也存了你的目录设置。";
    }

    private record RefreshResult(ScanNode root, Map<String, EntryStatus> statuses,
                                 List<CacheTablePanel.Row> rows, Exception error) {
    }
}
