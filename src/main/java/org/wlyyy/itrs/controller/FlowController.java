package org.wlyyy.itrs.controller;

import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.task.Task;
import org.apache.commons.io.IOUtils;
import org.codehaus.plexus.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.wlyyy.common.domain.*;
import org.wlyyy.itrs.dict.EnumFlowStatus;
import org.wlyyy.itrs.domain.*;
import org.wlyyy.itrs.event.ApplyFlowEvent;
import org.wlyyy.itrs.request.ApplyFlowQuery;
import org.wlyyy.itrs.request.CandidateQuery;
import org.wlyyy.itrs.request.DemandQuery;
import org.wlyyy.itrs.request.WorkFlowQuery;
import org.wlyyy.itrs.request.rest.CandidateRequest;
import org.wlyyy.itrs.service.*;
import org.wlyyy.itrs.vo.ApplyFlowListItemVo;
import org.wlyyy.itrs.vo.DeploymentListItemVo;
import org.wlyyy.itrs.vo.HistoricFlowListItemVo;

import javax.imageio.ImageIO;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/myProfile/flow")
public class FlowController {

    @SuppressWarnings("unused")
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FlowController.class);

    @Autowired
    private WorkFlowService workFlowService;

    @Autowired
    private DemandService demandService;

    @Autowired
    private RecommendService recommendService;

    @Autowired
    private CandidateService candidateService;

    @Autowired
    private UserService userService;

    @Autowired
    private ApplyFlowService applyFlowService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private PositionService positionService;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private ApplicationEventPublisher publisher;

    private final String NO_TASK = "-1";

    @RequestMapping("/deployFile")
    public BaseRestResponse<Deployment> deployWorkFlow_file() {
        // TODO
        return null;
    }

    /**
     * 根据classpath下的zip文件以及部署名称进行部署
     *
     * @param zipName    部署zip文件名（zip文件中包含.bpmn和.png）
     * @param deployName 流程名
     * @return 部署结果
     */
    @RequestMapping(value = "/deploy/deployZip", method = RequestMethod.GET)
    public BaseRestResponse<Deployment> deployWorkFlow_zip(String zipName, String deployName) {
        BaseServiceResponse<Deployment> deploymentResult = workFlowService.deployWorkFlow_zip(zipName, deployName);
        if (deploymentResult.isSuccess()) {
            Deployment deployment = deploymentResult.getData();
            return new BaseRestResponse<>(true, "流程部署成功", deployment);
        } else {
            return new BaseRestResponse<>(false, "流程部署失败", null);
        }
    }

    /**
     * 查询所有的部署信息
     *
     * @param pageNo   页码
     * @param pageSize 页数
     * @return 分页部署信息
     */
    @RequestMapping(value = "/deploy/listDeploy", method = RequestMethod.GET)
    BaseRestPageableResponse<DeploymentListItemVo> listDeploy(final int pageNo, final int pageSize) {
        BaseServicePageableResponse<Deployment> deploymentResult = workFlowService.findAllDeploy(new BaseServicePageableRequest<>(pageNo, pageSize,
                new WorkFlowQuery()));
        List<Deployment> deploymentList = deploymentResult.getDatas();
        List<DeploymentListItemVo> datas = deploymentList.stream().map(deployment -> DeploymentListItemVo.buildFromDomain(deployment,
                id -> workFlowService.findKeyByDeploymentId(id).getData().getKey()))
                .collect(Collectors.toList());
        return new BaseRestPageableResponse<>(true, "查询部署流程成功!", datas, pageNo, pageSize, deploymentResult.getTotal());
    }

    /**
     * 员工给某一招聘需求推荐人才
     *
     * @param demandId         招聘需求id
     * @param candidateRequest 候选人信息
     * @return 员工推荐成功or失败信息
     */
    @Transactional
    @RequestMapping(value = "/recommendTalent", method = RequestMethod.POST)
    public BaseRestResponse<String> recommendTalent(final Long demandId, final CandidateRequest candidateRequest) {
        final Candidate candidate = candidateRequest.buildCandidate();
        // 获取当前登录用户信息
        UserAgent userAgent = authenticationService.isLogin().getData();
        // 1. 获取该招聘需求对应的procKey
        Demand demand = demandService.findById(demandId);
        String procKey = demand.getProcKey();
        String demandNo = demand.getDemandNo();
        Long hrId = demand.getPublisherId();

        // 判断推荐人id与发布该条招聘信息的hr id是否相同，若相同，不能进行推荐
        if (userAgent.getId().equals(hrId)) {
            return new BaseRestResponse<>(false, "您发布的该招聘需求，无法推荐他人!", null);
        }

        // 2. 放入被推荐人信息到被推荐人信息表中
        // 先根据[被推荐人姓名+手机号]查询该被推荐人是否已存在被推荐人信息表中，若已存在，则查询其是否处于有效的招聘需求处理流程中
        // 若处于，则返回推荐失败；若不处于，则更新其在被推荐人信息表中的信息
        Long insertCandidateId;
        BaseServicePageableResponse<Candidate> recommendResult = candidateService.findByCondition(
                new BaseServicePageableRequest<>(1, 1,
                        new CandidateQuery().setName(candidate.getName()).setPhoneNo(candidate.getPhoneNo())));
        if (recommendResult.getTotal() == 1) {
            // 该被推荐人已存在于被推荐人信息表中
            // 获取其id
            Candidate exitsCandidate = recommendResult.getDatas().get(0);
            Long exitsCandidateId = exitsCandidate.getId();
            insertCandidateId = exitsCandidateId;
            // 查询其是否处于有效的招聘需求处理流程中
            BaseServicePageableResponse<ApplyFlow> applyFlowResult = applyFlowService.findByCondition(new BaseServicePageableRequest<>(1, 1,
                    new ApplyFlowQuery().setCandidateId(exitsCandidateId).setDemandNo(demandNo).setFlowStatus(EnumFlowStatus.EXECUTION.getCode())));
            if (applyFlowResult.getTotal() == 1) {
                // 处于有效的招聘需求处理流程中，返回推荐失败
                return new BaseRestResponse<>(false, "该推荐人已在有效的招聘流程中，请勿重复推荐该人!", null);
            } else {
                // 不处于，进行推荐人信息更新
                candidate.setId(exitsCandidateId);
                candidateService.updateCandidate(candidate);
            }
        } else {
            // 插入新被推荐人信息，并获取id
            insertCandidateId = candidateService.insertCandidate(candidate).getData();
        }

        // 3. 放入数据到推荐表中
        // 插入推荐表中
        Recommend recommend = new Recommend();
        recommend.setCandidateId(insertCandidateId);
        recommend.setUserId(userAgent.getId());
        recommendService.insertRecommend(recommend);

        // 4. 放入数据到招聘流程信息表中
        ApplyFlow applyFlow = new ApplyFlow();
        applyFlow.setDemandNo(demandNo);
        applyFlow.setCandidateId(insertCandidateId);
        applyFlow.setUserId(userAgent.getId());
        applyFlow.setCurrentFlowNode("员工推荐");
        applyFlow.setCurrentDealer(userAgent.getId());
        applyFlow.setFlowStatus(EnumFlowStatus.EXECUTION.getCode());
        Long applyFlowId = applyFlowService.insertApplyFlow(applyFlow).getData();

        // 5. 启动流程实例，同时设置下一任务处理人是自己
        WorkFlow workFlow = new WorkFlow();
        workFlow.setProcKey(procKey);
        workFlow.setId(applyFlowId);
        workFlow.setRecommendId(userAgent.getId());
        String processInstanceId = workFlowService.startProcess(workFlow).getData().getProcessInstanceId();

        // 6. 根据任务id来完成任务，同时设置下一任务的处理人
        Task task = workFlowService.findRecommendTask(
                new WorkFlowQuery().setRecommendId(userAgent.getId()).setProcessInstanceId(processInstanceId).setTaskName("员工推荐"));
        workFlow.setTaskId(task.getId());
        workFlow.setPublisherId(hrId);
        BaseServiceResponse<String> completeResult = workFlowService.completeRecommendTask(workFlow);
        if (completeResult.isSuccess()) {
            // 更改当前流程节点和当前处理人
            applyFlow.setId(applyFlowId);
            applyFlow.setCurrentFlowNode("筛选简历");
            applyFlow.setCurrentDealer(hrId);
            applyFlow.setCurrentResult("待筛选简历");
            applyFlowService.updateApplyFlow(applyFlow);
            return new BaseRestResponse<>(true, "员工推荐成功!", null);
        } else {
            applyFlow.setId(applyFlowId);
            applyFlow.setCurrentResult("推荐异常");
            applyFlow.setFlowStatus(EnumFlowStatus.EXCEPTION.getCode());
            // 异常，更新流程状态为“异常”
            applyFlowService.updateApplyFlow(applyFlow);
            return new BaseRestResponse<>(false, "员工推荐失败或出现异常!", null);
        }
    }

    /**
     * 根据招聘需求编号展示其下的展示层招聘流程信息表（给hr用）
     *
     * @param demandId 招聘需求id
     * @return ApplyFlowListItemVo列表
     */
    @RequestMapping(value = "/listApplyFlowHr/{demandId}", method = RequestMethod.GET)
    BaseRestPageableResponse<ApplyFlowListItemVo> queryApplyFlowListByDemandIdForHr(@PathVariable("demandId") final Long demandId) {
        int pageNo = 1;
        int pageSize = Integer.MAX_VALUE;
        // 获取当前登录用户信息
        UserAgent userAgent = authenticationService.isLogin().getData();
        Demand demand = demandService.findById(demandId);
        String demandNo = demand.getDemandNo();

        // 1. 根据招聘需求id找到其下的招聘流程列表
        Sort sort = new Sort(new Order(Sort.Direction.DESC, "gmt_modify"));
        BaseServicePageableRequest<ApplyFlowQuery> request = new BaseServicePageableRequest<>(pageNo, pageSize,
                new ApplyFlowQuery().setDemandNo(demandNo).setSort(sort));
        BaseServicePageableResponse<ApplyFlow> applyFlowResult = applyFlowService.findByCondition(request);
        List<ApplyFlow> applyFlowList = applyFlowResult.getDatas();

        // 2. 转化成的展示层招聘流程信息表
        // 在根据招聘流程id得到任务id和操作的过程中，若当前处理人不是该登录用户，则置任务id为-1，操作为空
        List<ApplyFlowListItemVo> datas = applyFlowList.stream().map(source -> ApplyFlowListItemVo.buildFromDomain(source,
                (cid) -> candidateService.findById(cid),
                (uid) -> {
                    if (uid == 0L) {
                        return "无";
                    }
                    return userService.findById(uid).getRealName();
                },
                (aid) -> {
                    if (!source.getCurrentDealer().equals(userAgent.getId())) {
                        return NO_TASK;
                    } else {
                        Task currentTask = workFlowService.findCurrentTaskByApplyId(aid).getData();
                        if (currentTask == null) {
                            return NO_TASK;
                        } else {
                            return currentTask.getId();
                        }
                    }
                },
                (aid) -> {
                    Task currentTask = workFlowService.findCurrentTaskByApplyId(aid).getData();
                    if (currentTask == null) {
                        return "无当前任务";
                    } else {
                        return currentTask.getName();
                    }
                },
                (aid) -> {
                    if (!source.getCurrentDealer().equals(userAgent.getId())) {
                        return new ArrayList<String>();
                    }
                    return workFlowService.findCurrentOutcomeListByApplyId(aid).getData();
                },
                (dno) -> demandService.findByNo(dno),
                (pt) -> positionService.getPositionTypeCnName(pt)))
                .collect(Collectors.toList());
        return new BaseRestPageableResponse<>(true, "查询展示层招聘流程信息表成功!", datas,
                applyFlowResult.getPageNo(), applyFlowResult.getPageSize(), applyFlowResult.getTotal());
    }

    /**
     * 展示该用户下的所有展示层招聘流程信息表（给面试官用）
     *
     * @param pageNo   页码
     * @param pageSize 分页大小
     * @return ApplyFlowListItemVo列表
     */
    @RequestMapping(value = "/listApplyFlowInterviewee", method = RequestMethod.GET)
    BaseRestPageableResponse<ApplyFlowListItemVo> queryApplyFlowListForInterviewee(final int pageNo, final int pageSize) {
        // 获取当前登录用户信息
        UserAgent userAgent = authenticationService.isLogin().getData();

        // 1. 根据用户id找到其下的招聘流程列表
        Sort sort = new Sort(new Order(Sort.Direction.DESC, "gmt_modify"));
        // 需要排除掉该用户发布的招聘需求No
        // 因为hr也可以作为面试官，但规定其不得指派自己为自己发布的招聘需求的面试官
        BaseServicePageableRequest<ApplyFlowQuery> request = new BaseServicePageableRequest<>(pageNo, pageSize,
                new ApplyFlowQuery().setCurrentDealer(userAgent.getId()).setSort(sort));
        // 该用户发布的招聘需求No列表
        BaseServicePageableRequest<DemandQuery> requestDemand = new BaseServicePageableRequest<>(1, Integer.MAX_VALUE,
                new DemandQuery().setPublisherId(userAgent.getId()));
        List<Demand> demandList = demandService.findByCondition(requestDemand).getDatas();
        List<String> demandNoList = demandList.stream().map(Demand::getDemandNo).collect(Collectors.toList());

        BaseServicePageableResponse<ApplyFlow> applyFlowResult = applyFlowService.findNotInDemandNo(request, demandNoList);
        List<ApplyFlow> applyFlowList = applyFlowResult.getDatas();

        // 2. 转化成的展示层招聘流程信息表
        // 在根据招聘流程id得到任务id和操作的过程中，若当前处理人不是该登录用户，则置任务id为-1，操作为空
        List<ApplyFlowListItemVo> datas = applyFlowList.stream().map(source -> ApplyFlowListItemVo.buildFromDomain(source,
                (cid) -> candidateService.findById(cid),
                (uid) -> {
                    if (uid == 0L) {
                        return "无";
                    }
                    return userService.findById(uid).getRealName();
                },
                (aid) -> {
                    if (!source.getCurrentDealer().equals(userAgent.getId())) {
                        return NO_TASK;
                    } else {
                        Task currentTask = workFlowService.findCurrentTaskByApplyId(aid).getData();
                        if (currentTask == null) {
                            return NO_TASK;
                        } else {
                            return currentTask.getId();
                        }
                    }
                },
                (aid) -> {
                    Task currentTask = workFlowService.findCurrentTaskByApplyId(aid).getData();
                    if (currentTask == null) {
                        return "无当前任务";
                    } else {
                        return currentTask.getName();
                    }
                },
                (aid) -> {
                    if (!source.getCurrentDealer().equals(userAgent.getId())) {
                        return new ArrayList<String>();
                    }
                    return workFlowService.findCurrentOutcomeListByApplyId(aid).getData();
                },
                (dno) -> demandService.findByNo(dno),
                (pt) -> positionService.getPositionTypeCnName(pt)))
                .collect(Collectors.toList());
        return new BaseRestPageableResponse<>(true, "查询展示层招聘流程信息表成功!", datas,
                applyFlowResult.getPageNo(), applyFlowResult.getPageSize(), applyFlowResult.getTotal());
    }

    /**
     * 展示该用户下的所有展示层招聘流程信息表（给推荐人用）
     *
     * @param pageNo   页码
     * @param pageSize 分页大小
     * @return ApplyFlowListItemVo列表
     */
    @RequestMapping(value = "/listApplyFlowRecommender", method = RequestMethod.GET)
    BaseRestPageableResponse<ApplyFlowListItemVo> queryApplyFlowListForRecommender(final int pageNo, final int pageSize) {
        // 获取当前登录用户信息
        UserAgent userAgent = authenticationService.isLogin().getData();

        // 1. 根据用户id找到其下的招聘流程列表
        Sort sort = new Sort(new Order(Sort.Direction.DESC, "gmt_modify"));
        BaseServicePageableRequest<ApplyFlowQuery> request = new BaseServicePageableRequest<>(pageNo, pageSize,
                new ApplyFlowQuery().setUserId(userAgent.getId()).setSort(sort));
        BaseServicePageableResponse<ApplyFlow> applyFlowResult = applyFlowService.findByCondition(request);
        List<ApplyFlow> applyFlowList = applyFlowResult.getDatas();

        // 2. 转化成的展示层招聘流程信息表
        // 在根据招聘流程id得到任务id和操作的过程中，若当前处理人不是该登录用户，则置任务id为-1，操作为空
        List<ApplyFlowListItemVo> datas = applyFlowList.stream().map(source -> ApplyFlowListItemVo.buildFromDomain(source,
                (cid) -> candidateService.findById(cid),
                (uid) -> {
                    if (uid == 0L) {
                        return "无";
                    }
                    return userService.findById(uid).getRealName();
                },
                (aid) -> NO_TASK,
                (aid) -> {
                    Task currentTask = workFlowService.findCurrentTaskByApplyId(aid).getData();
                    if (currentTask == null) {
                        return "无当前任务";
                    } else {
                        return currentTask.getName();
                    }
                },
                (aid) -> new ArrayList<String>(),
                (dno) -> demandService.findByNo(dno),
                (pt) -> positionService.getPositionTypeCnName(pt)))
                .collect(Collectors.toList());
        return new BaseRestPageableResponse<>(true, "查询展示层招聘流程信息表成功!", datas,
                applyFlowResult.getPageNo(), applyFlowResult.getPageSize(), applyFlowResult.getTotal());
    }

    /**
     * 根据招聘需求编号展示其下的展示层招聘流程信息表（给部门经理用）
     *
     * @param demandId 招聘需求id
     * @return ApplyFlowListItemVo列表
     */
    @RequestMapping(value = "/listApplyFlowManager/{demandId}", method = RequestMethod.GET)
    BaseRestPageableResponse<ApplyFlowListItemVo> queryApplyFlowListByDemandIdForManager(@PathVariable("demandId") final Long demandId) {
        int pageNo = 1;
        int pageSize = Integer.MAX_VALUE;
        Demand demand = demandService.findById(demandId);
        String demandNo = demand.getDemandNo();

        // 1. 根据招聘需求id找到其下的招聘流程列表
        Sort sort = new Sort(new Order(Sort.Direction.DESC, "gmt_modify"));
        BaseServicePageableRequest<ApplyFlowQuery> request = new BaseServicePageableRequest<>(pageNo, pageSize,
                new ApplyFlowQuery().setDemandNo(demandNo).setSort(sort));
        BaseServicePageableResponse<ApplyFlow> applyFlowResult = applyFlowService.findByCondition(request);
        List<ApplyFlow> applyFlowList = applyFlowResult.getDatas();

        // 2. 转化成的展示层招聘流程信息表
        // 在根据招聘流程id得到任务id和操作的过程中，若当前处理人不是该登录用户，则置任务id为-1，操作为空
        List<ApplyFlowListItemVo> datas = applyFlowList.stream().map(source -> ApplyFlowListItemVo.buildFromDomain(source,
                (cid) -> candidateService.findById(cid),
                (uid) -> {
                    if (uid == 0L) {
                        return "无";
                    }
                    return userService.findById(uid).getRealName();
                },
                (aid) -> NO_TASK,
                (aid) -> {
                    Task currentTask = workFlowService.findCurrentTaskByApplyId(aid).getData();
                    if (currentTask == null) {
                        return "无当前任务";
                    } else {
                        return currentTask.getName();
                    }
                },
                (aid) -> new ArrayList<String>(),
                (dno) -> demandService.findByNo(dno),
                (pt) -> positionService.getPositionTypeCnName(pt)))
                .collect(Collectors.toList());
        return new BaseRestPageableResponse<>(true, "查询展示层招聘流程信息表成功!", datas,
                applyFlowResult.getPageNo(), applyFlowResult.getPageSize(), applyFlowResult.getTotal());
    }


    /**
     * 查询当前用户的历史处理记录
     *
     * @param pageNo   页码
     * @param pageSize 分页大小
     * @return HistoricFlowListItemVo列表
     */
    @RequestMapping(value = "listHistoricFlow", method = RequestMethod.GET)
    BaseRestPageableResponse<HistoricFlowListItemVo> queryHistoricFlowList(int pageNo, int pageSize) {
        // 获取当前登录用户信息
        UserAgent userAgent = authenticationService.isLogin().getData();
        BaseServicePageableRequest<WorkFlowQuery> request = new BaseServicePageableRequest<>(pageNo, pageSize, new WorkFlowQuery());
        BaseServicePageableResponse<HistoricTaskInstance> historicTaskResult = workFlowService.findHistoricTaskByAssignee(request, userAgent);
        List<HistoricTaskInstance> historicTaskList = historicTaskResult.getDatas();
        List<HistoricFlowListItemVo> datas = historicTaskList.stream().map(source -> HistoricFlowListItemVo.buildFromDomain(source,
                (taskId) -> {
                    String result = workFlowService.findVarValueByTaskIdAndVarName(taskId, "currentResult").getData();
                    if (StringUtils.isBlank(result)) {
                        return "无";
                    }
                    return result;
                },
                (pid) -> workFlowService.findApplyIdByProcInstanceId(pid).getData(),
                (aid) -> applyFlowService.findById(aid).getDemandNo()))
                .collect(Collectors.toList());

        return new BaseRestPageableResponse<>(true, "查询历史操作信息成功!", datas, pageNo, pageSize, historicTaskResult.getTotal());
    }

    /**
     * 用户根据任务id处理任务，同时给出任务结果和下一任务执行人，并更新当前流程节点、当前处理人、当前结果和流程状态
     * 要求传入outcome(必须), nextUserId(除最后一个节点外，必须，且只能有一个), result, taskId(必须), applyFlowId(必须)
     *
     * @param workFlow workFlow对象
     * @return 成功or失败信息
     */
    @Transactional
    @RequestMapping(value = "/deal", method = RequestMethod.POST)
    BaseRestResponse<String> dealApplyFlow(final WorkFlow workFlow) {
        // 1. 用户完成任务
        // 只能有一个下一任务执行人
        // 且hr指派的面试官不能为推荐员工
//        if (workFlow.getRecommendId().equals(workFlow.getNextUserId())) {
//            return new BaseRestResponse<>(false, "不得指派推荐该被推荐人的员工作为面试官!", null);
//        }
        BaseServiceResponse<String> completeTaskResult = workFlowService.completeTaskByTaskId(workFlow);
        // 完成任务不成功，返回失败
        if (!completeTaskResult.isSuccess()) {
            return new BaseRestResponse<>(false, completeTaskResult.getMessage(), null);
        }

        // 2. 更新当前流程节点、当前处理人、当前结果和流程状态
        // 先判断流程是否结束，若结束，更新流程状态为“结束”、当前流程节点为“已结束”、当前处理人为“无”
        ApplyFlow applyFlow = new ApplyFlow();
        applyFlow.setId(workFlow.getId());
        applyFlow.setCurrentResult(workFlow.getResult());
        Boolean isFinish = workFlowService.isFinishByApplyId(workFlow.getId()).getData();
        if (isFinish) {
            applyFlow.setFlowStatus(EnumFlowStatus.FINISH.getCode());
            applyFlow.setCurrentFlowNode("已结束");
            applyFlow.setCurrentDealer(0L);
        } else {
            // 根据招聘流程id找到当前正在处理的Task
            BaseServiceResponse<Task> taskResult = workFlowService.findCurrentTaskByApplyId(workFlow.getId());
            Task currentTask = taskResult.getData();
            String currentFlowNode = currentTask.getName();
            Long currentDealer = Long.parseLong(currentTask.getAssignee());
            applyFlow.setCurrentFlowNode(currentFlowNode);
            applyFlow.setCurrentDealer(currentDealer);
        }

        applyFlowService.updateApplyFlow(applyFlow);

        // 3. 通知监听ApplyFlowEvent事件的，进行积分变动处理
        ApplyFlow applyFlowEvent = applyFlowService.findById(workFlow.getId());
        applyFlowEvent.setCurrentResult(workFlow.getResult());
        // 发送事件通知
        String otherMessage = workFlow.getOutcome(); // 其他携带的信息
        this.publisher.publishEvent(new ApplyFlowEvent(applyFlowEvent, otherMessage));

        return new BaseRestResponse<>(true, "完成任务成功!", null);
    }

    @RequestMapping(value = "/deploy/getDeployPicture", method = RequestMethod.GET)
    public void getDeployPicture(final HttpServletResponse response, final String deploymentId, final String resourceName) throws IOException {
        response.setHeader("Content-Type", " image/png");
        response.setHeader("Content-Disposition", "attachment;filename=" + resourceName);
        try (InputStream ins = workFlowService.getDeployPictureInputStream(deploymentId, resourceName); ServletOutputStream ous = response.getOutputStream()) {
            final BufferedImage imBuff = ImageIO.read(ins);
            final BufferedImage resultImage = getCroppedImage(imBuff, 0);
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            ImageIO.write(resultImage, "png", os);
            IOUtils.copy(new ByteArrayInputStream(os.toByteArray()), ous);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public BufferedImage getCroppedImage(BufferedImage source, double tolerance) {
        // Get our top-left pixel color as our "baseline" for cropping
        int baseColor = source.getRGB(0, 0);
        int width = source.getWidth();
        int height = source.getHeight();
        int topY = Integer.MAX_VALUE, topX = Integer.MAX_VALUE;
        int bottomY = -1, bottomX = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (colorWithinTolerance(baseColor, source.getRGB(x, y), tolerance)) {
                    if (x < topX) topX = x;
                    if (y < topY) topY = y;
                    if (x > bottomX) bottomX = x;
                    if (y > bottomY) bottomY = y;
                }
            }
        }
        BufferedImage destination = new BufferedImage((bottomX - topX + 2), (bottomY - topY + 2), BufferedImage.TYPE_INT_ARGB);
        destination.getGraphics().drawImage(source, 0, 0,
                destination.getWidth(), destination.getHeight(),
                topX, topY, bottomX + 1, bottomY + 1, null);
        return destination;
    }

    /**
     * 非白色
     *
     * @param a
     * @param b
     * @param tolerance
     * @return
     */
    private Boolean colorWithinTolerance(int a, int b, double tolerance) {
        int aAlpha = (int) ((a & 0xFF000000) >>> 24);   // Alpha level
        int aRed = (int) ((a & 0x00FF0000) >>> 16);   // Red level
        int aGreen = (int) ((a & 0x0000FF00) >>> 8);    // Green level
        int aBlue = (int) (a & 0x000000FF);            // Blue level
        int bAlpha = (int) ((b & 0xFF000000) >>> 24);   // Alpha level
        int bRed = (int) ((b & 0x00FF0000) >>> 16);   // Red level
        int bGreen = (int) ((b & 0x0000FF00) >>> 8);    // Green level
        int bBlue = (int) (b & 0x000000FF);            // Blue level
        double distance = Math.sqrt((aAlpha - bAlpha) * (aAlpha - bAlpha) +
                (aRed - bRed) * (aRed - bRed) +
                (aGreen - bGreen) * (aGreen - bGreen) +
                (aBlue - bBlue) * (aBlue - bBlue));
        // 510.0 is the maximum distance between two colors 
        // (0,0,0,0 -> 255,255,255,255)
        double percentAway = distance / 510.0d;
        return (percentAway > tolerance);
    }


    @RequestMapping(value = "/send", method = RequestMethod.GET)
    public BaseRestResponse<String> sendScore() {
        ApplyFlow applyFlow = applyFlowService.findById(9L);
        ApplyFlowEvent event = new ApplyFlowEvent(applyFlow, "otherMessage");
        this.publisher.publishEvent(event);
        return new BaseRestResponse<>(true, "发送招聘流程成功!", null);
    }

    @RequestMapping(value = "/createUser")
    String createUser(String username, String password) {
        User user = new User();
        user.setUserName(username);
        user.setPassword(password);
        user.setEmail("397055871@qq.com");
        user.setSex(1);
        user.setRealName("翁啦啦");
        user.setDepartmentId(1L);
        userService.createUser(user);
        return "Create user success!";
    }
}
