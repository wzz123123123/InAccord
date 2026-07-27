const navItems = ['工作台', '需求空间', '开发协同', '项目配置'];

const requirementGroups = [
  { name: '客户与账户', count: 8 },
  { name: '合同与履约', count: 12 },
  { name: '审批与权限', count: 6 },
  { name: '数据与报表', count: 4 }
];

const requirements = [
  {
    id: 'REQ-024',
    title: '合同变更需要双侧确认后进入开发',
    group: '合同与履约',
    status: '待开发确认',
    tone: 'warning',
    score: '82',
    owner: '陈雨',
    updatedAt: '10 分钟前'
  },
  {
    id: 'REQ-021',
    title: '客户材料上传并关联到需求块',
    group: '客户与账户',
    status: '影响分析中',
    tone: 'info',
    score: '76',
    owner: '林哲',
    updatedAt: '今天 09:42'
  },
  {
    id: 'REQ-018',
    title: '严格模式允许管理员一人多兼',
    group: '审批与权限',
    status: '双方已确认',
    tone: 'success',
    score: '91',
    owner: '周敏',
    updatedAt: '昨天 17:26'
  }
];

export function App() {
  return (
    <div className="app-shell">
      <aside className="global-nav" aria-label="全局导航">
        <div className="brand" aria-label="合契 Accord">
          <span className="brand-mark">A</span>
          <span>
            <strong>合契</strong>
            <small>ACCORD</small>
          </span>
        </div>
        <nav>
          {navItems.map((item, index) => (
            <button className={index === 0 ? 'nav-item active' : 'nav-item'} key={item} type="button">
              <span className="nav-index">0{index + 1}</span>
              <span>{item}</span>
            </button>
          ))}
        </nav>
        <div className="nav-footer">
          <span className="environment-dot" aria-hidden="true" />
          <span><strong>演示环境</strong><small>服务运行正常</small></span>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div className="project-switcher">
            <span className="label">当前项目</span>
            <button type="button">新一代合同协同平台 <span aria-hidden="true">⌄</span></button>
          </div>
          <div className="topbar-actions">
            <button className="icon-button" type="button" aria-label="搜索">⌕</button>
            <button className="icon-button notification" type="button" aria-label="通知">◌<span>3</span></button>
            <div className="account"><span>需求</span><strong>王嘉宁</strong></div>
          </div>
        </header>

        <main className="content">
          <section className="page-heading" aria-labelledby="page-title">
            <div>
              <div className="breadcrumb">需求空间 / 业务需求视图</div>
              <h1 id="page-title">需求协同工作台</h1>
              <p>围绕业务目标组织需求，并与开发侧持续确认影响与交付边界。</p>
            </div>
            <button className="primary-action" type="button"><span aria-hidden="true">＋</span> 新增需求</button>
          </section>

          <section className="status-strip" aria-label="项目状态摘要">
            <div><span>进行中需求</span><strong>18</strong><small>本周 +4</small></div>
            <div><span>等待我确认</span><strong>5</strong><small className="attention">需要处理</small></div>
            <div><span>平均评估分</span><strong>84</strong><small>稳定</small></div>
            <div><span>开发中</span><strong>7</strong><small>2 个本周交付</small></div>
          </section>

          <div className="board-layout">
            <aside className="group-panel" aria-label="需求分类">
              <div className="panel-title"><strong>需求分类</strong><button type="button" aria-label="分类设置">•••</button></div>
              <button className="group-item selected" type="button"><span>全部需求</span><strong>30</strong></button>
              {requirementGroups.map((group) => (
                <button className="group-item" key={group.name} type="button">
                  <span>{group.name}</span><strong>{group.count}</strong>
                </button>
              ))}
              <button className="add-group" type="button">＋ 新增分类</button>
            </aside>

            <section className="requirement-list" aria-labelledby="list-title">
              <div className="list-toolbar">
                <div><h2 id="list-title">全部需求</h2><span>30 项</span></div>
                <div className="view-actions">
                  <button className="active" type="button">需求块</button>
                  <button type="button">时间线</button>
                  <button className="filter-button" type="button">筛选 <span>2</span></button>
                </div>
              </div>
              <div className="column-labels" aria-hidden="true">
                <span>需求与业务分类</span><span>综合评分</span><span>负责人</span><span>最近更新</span>
              </div>
              <div className="requirement-rows">
                {requirements.map((requirement) => (
                  <article className="requirement-row" key={requirement.id}>
                    <div className="requirement-main">
                      <div className="requirement-meta"><code>{requirement.id}</code><span>{requirement.group}</span></div>
                      <h3>{requirement.title}</h3>
                      <span className={`state ${requirement.tone}`}>{requirement.status}</span>
                    </div>
                    <div className="score"><strong>{requirement.score}</strong><span>/ 100</span></div>
                    <div className="owner"><span>{requirement.owner.slice(0, 1)}</span>{requirement.owner}</div>
                    <time>{requirement.updatedAt}</time>
                    <button className="row-open" type="button" aria-label={`打开 ${requirement.id}`}>›</button>
                  </article>
                ))}
              </div>
            </section>

            <aside className="activity-panel" aria-labelledby="activity-title">
              <div className="panel-title"><strong id="activity-title">待办与动态</strong><button type="button">查看全部</button></div>
              <div className="task-callout">
                <span className="task-label">最高优先级</span>
                <strong>确认 REQ-024 的预期效果</strong>
                <p>开发侧已完成影响分析并提出 2 条修改建议。</p>
                <button type="button">现在处理</button>
              </div>
              <ol className="activity-list">
                <li><span className="activity-dot green" /><div><strong>双方确认完成</strong><p>REQ-018 已进入待开发队列</p><time>32 分钟前</time></div></li>
                <li><span className="activity-dot amber" /><div><strong>开发侧提出建议</strong><p>REQ-024 有新的边界说明</p><time>1 小时前</time></div></li>
                <li><span className="activity-dot gray" /><div><strong>材料分析完成</strong><p>3 份附件已关联 REQ-021</p><time>今天 09:42</time></div></li>
              </ol>
            </aside>
          </div>
        </main>
      </div>
    </div>
  );
}
