package xiangshan.backend.fu.wrapper

import chisel3._
import org.chipsalliance.cde.config.Parameters
import utility._
import xiangshan._
import xiangshan.backend.fu.NewCSR.{CSRPermitModule, NewCSR}
import xiangshan.backend.fu.util._
import xiangshan.backend.fu.{FuConfig, FuncUnit}
import device._

class CSR(cfg: FuConfig)(implicit p: Parameters) extends FuncUnit(cfg)
{
  val csrIn = io.csrio.get
  val csrOut = io.csrio.get

  val setFsDirty = csrIn.fpu.dirty_fs
  val setFflags = csrIn.fpu.fflags
  val setVsDirty = csrIn.vpu.dirty_vs
  val setVxsat = csrIn.vpu.vxsat

  //val flushPipe = Wire(Bool()) // Todo

  val (valid, src1, src2, func) = (
    io.in.valid,
    io.in.bits.data.src(0),
    io.in.bits.data.imm,
    io.in.bits.ctrl.fuOpType
  )

  // split imm from IMM_Z
  val addr = src2(11, 0)
  val csri = src2(16, 12)

  import CSRConst._

  private val isEcall  = CSROpType.isSystemOp(func) && addr === privEcall
  private val isEbreak = CSROpType.isSystemOp(func) && addr === privEbreak
  private val isMret   = CSROpType.isSystemOp(func) && addr === privMret
  private val isSret   = CSROpType.isSystemOp(func) && addr === privSret
  private val isDret   = CSROpType.isSystemOp(func) && addr === privDret
  private val isWfi    = CSROpType.isWfi(func)

  val permitMod = Module(new CSRPermitModule)
  val csrMod = Module(new NewCSR)

  private val privState = csrMod.io.out.privState
  // The real reg value in CSR, with no read mask
  private val regOut = csrMod.io.out.regOut
  // The read data with read mask
  private val rdata = csrMod.io.out.rData
  private val wdata = LookupTree(func, Seq(
    CSROpType.wrt  -> src1,
    CSROpType.set  -> (regOut | src1),
    CSROpType.clr  -> (regOut & (~src1).asUInt),
    CSROpType.wrti -> csri,
    CSROpType.seti -> (regOut | csri),
    CSROpType.clri -> (regOut & (~csri).asUInt),
  ))

  private val csrAccess = valid && CSROpType.isCsrAccess(func)
  private val csrWen = valid && CSROpType.notReadOnly(func)

  permitMod.io.in.wen       := csrWen
  permitMod.io.in.addr      := addr
  permitMod.io.in.privState := csrMod.io.out.privState

  csrMod.io.in match {
    case in =>
      in.wen := csrWen && permitMod.io.out.legal
      in.ren := csrAccess
      in.addr := addr
      in.wdata := wdata
  }
  csrMod.io.fromMem.excpVaddr := csrIn.memExceptionVAddr
  csrMod.io.fromMem.excpGPA := 0.U // Todo
  csrMod.io.fromMem.excpGVA := 0.U // Todo

  csrMod.io.fromRob.trap.valid := csrIn.exception.valid
  csrMod.io.fromRob.trap.bits.pc := csrIn.exception.bits.pc
  csrMod.io.fromRob.trap.bits.instr := csrIn.exception.bits.instr
  csrMod.io.fromRob.trap.bits.trapVec := csrIn.exception.bits.exceptionVec
  csrMod.io.fromRob.trap.bits.singleStep := csrIn.exception.bits.singleStep
  csrMod.io.fromRob.trap.bits.crossPageIPFFix := csrIn.exception.bits.crossPageIPFFix
  csrMod.io.fromRob.trap.bits.isInterrupt := csrIn.exception.bits.isInterrupt

  csrMod.io.fromRob.commit.fflags := setFflags
  csrMod.io.fromRob.commit.fsDirty := setFsDirty
  csrMod.io.fromRob.commit.vxsat.valid := true.B // Todo:
  csrMod.io.fromRob.commit.vxsat.bits := setVxsat // Todo:
  csrMod.io.fromRob.commit.vsDirty := setVsDirty
  csrMod.io.fromRob.commit.commitValid := false.B // Todo:
  csrMod.io.fromRob.commit.commitInstRet := 0.U // Todo:

  csrMod.io.mret := isMret
  csrMod.io.sret := isSret
  csrMod.io.dret := isDret
  csrMod.io.wfi  := isWfi

  csrMod.platformIRP.MEIP := csrIn.externalInterrupt.meip
  csrMod.platformIRP.MTIP := csrIn.externalInterrupt.mtip
  csrMod.platformIRP.MSIP := csrIn.externalInterrupt.msip
  csrMod.platformIRP.SEIP := csrIn.externalInterrupt.seip
  csrMod.platformIRP.VSEIP := false.B // Todo
  csrMod.platformIRP.VSTIP := false.B // Todo

  private val imsic = Module(new IMSIC)
  imsic.i.hartId := io.csrin.get.hartId
  imsic.i.setIpNumValidVec2 := io.csrin.get.setIpNumValidVec2
  imsic.i.setIpNum.valid := true.B // Todo:
  imsic.i.setIpNum.bits := io.csrin.get.setIpNum // Todo:
  imsic.i.csr.addr.valid := csrMod.toAIA.addr.valid
  imsic.i.csr.addr.bits.addr := csrMod.toAIA.addr.bits.addr
  imsic.i.csr.addr.bits.prvm := csrMod.toAIA.addr.bits.prvm.asUInt
  imsic.i.csr.addr.bits.v := csrMod.toAIA.addr.bits.v.asUInt
  imsic.i.csr.vgein := csrMod.toAIA.vgein
  imsic.i.csr.mClaim := csrMod.toAIA.mClaim
  imsic.i.csr.sClaim := csrMod.toAIA.sClaim
  imsic.i.csr.vsClaim := csrMod.toAIA.vsClaim
  imsic.i.csr.wdata.valid := csrMod.toAIA.wdata.valid
  imsic.i.csr.wdata.bits.data := csrMod.toAIA.wdata.bits.data

  csrMod.fromAIA.rdata.valid := imsic.o.csr.rdata.valid
  csrMod.fromAIA.rdata.bits.data := imsic.o.csr.rdata.bits.rdata
  csrMod.fromAIA.rdata.bits.illegal := imsic.o.csr.rdata.bits.illegal
  csrMod.fromAIA.mtopei.valid := imsic.o.mtopei.valid
  csrMod.fromAIA.stopei.valid := imsic.o.stopei.valid
  csrMod.fromAIA.vstopei.valid := imsic.o.vstopei.valid
  csrMod.fromAIA.mtopei.bits := imsic.o.mtopei.bits
  csrMod.fromAIA.stopei.bits := imsic.o.stopei.bits
  csrMod.fromAIA.vstopei.bits := imsic.o.vstopei.bits

  private val exceptionVec = WireInit(VecInit(Seq.fill(16)(false.B))) // Todo:
  import ExceptionNO._
  exceptionVec(EX_BP    ) := isEbreak
  exceptionVec(EX_MCALL ) := isEcall && privState.isModeM
  exceptionVec(EX_HSCALL) := isEcall && privState.isModeHS
  exceptionVec(EX_VSCALL) := isEcall && privState.isModeVS
  exceptionVec(EX_UCALL ) := isEcall && privState.isModeHUorVU
  exceptionVec(EX_II    ) := csrMod.io.out.EX_II
  //exceptionVec(EX_VI    ) := csrMod.io.out.EX_VI // Todo: check other EX_VI

  io.in.ready := true.B // Todo: Async read imsic may block CSR
  io.out.valid := valid
  io.out.bits.ctrl.exceptionVec.get := exceptionVec
  io.out.bits.ctrl.flushPipe.get := csrMod.io.out.flushPipe
  io.out.bits.res.data := csrMod.io.out.rData
  connect0LatencyCtrlSingal

  csrOut.isPerfCnt := DontCare
  csrOut.fpu.frm := csrMod.io.out.frm
  csrOut.vpu.vstart := DontCare
  csrOut.vpu.vxsat := DontCare
  csrOut.vpu.vxrm := csrMod.io.out.vxrm
  csrOut.vpu.vcsr := DontCare
  csrOut.vpu.vl := DontCare
  csrOut.vpu.vtype := DontCare
  csrOut.vpu.vlenb := DontCare
  csrOut.vpu.vill := DontCare
  csrOut.vpu.vma := DontCare
  csrOut.vpu.vta := DontCare
  csrOut.vpu.vsew := DontCare
  csrOut.vpu.vlmul := DontCare

  csrOut.isXRet := DontCare

  csrOut.trapTarget := csrMod.io.out.targetPc
  csrOut.interrupt := DontCare
  csrOut.wfi_event := DontCare

  csrOut.tlb := DontCare

  csrOut.debugMode := DontCare

  csrOut.disableSfence := DontCare

  csrOut.customCtrl match {
    case custom =>
      custom.l1I_pf_enable := DontCare
      custom.l2_pf_enable := DontCare
      custom.l1D_pf_enable := DontCare
      custom.l1D_pf_train_on_hit := DontCare
      custom.l1D_pf_enable_agt := DontCare
      custom.l1D_pf_enable_pht := DontCare
      custom.l1D_pf_active_threshold := DontCare
      custom.l1D_pf_active_stride := DontCare
      custom.l1D_pf_enable_stride := DontCare
      custom.l2_pf_store_only := DontCare
      // ICache
      custom.icache_parity_enable := DontCare
      // Labeled XiangShan
      custom.dsid := DontCare
      // Load violation predictor
      custom.lvpred_disable := DontCare
      custom.no_spec_load := DontCare
      custom.storeset_wait_store := DontCare
      custom.storeset_no_fast_wakeup := DontCare
      custom.lvpred_timeout := DontCare
      // Branch predictor
      custom.bp_ctrl := DontCare
      // Memory Block
      custom.sbuffer_threshold := DontCare
      custom.ldld_vio_check_enable := DontCare
      custom.soft_prefetch_enable := DontCare
      custom.cache_error_enable := DontCare
      custom.uncache_write_outstanding_enable := DontCare
      // Rename
      custom.fusion_enable := DontCare
      custom.wfi_enable := DontCare
      // Decode
      custom.svinval_enable := DontCare
      // distribute csr write signal
      // write to frontend and memory
      custom.distribute_csr := DontCare
      // rename single step
      custom.singlestep := DontCare
      // trigger
      custom.frontend_trigger := DontCare
      custom.mem_trigger := DontCare
  }
}

class CSRInput(implicit p: Parameters) extends XSBundle {
  val hartId = Input(UInt(8.W))
  val setIpNumValidVec2 = Input(Vec(2, Vec(7, Bool())))
  val setIpNum = Input(UInt(4.W))
}