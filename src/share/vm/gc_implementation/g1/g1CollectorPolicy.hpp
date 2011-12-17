/*
 * Copyright (c) 2001, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_VM_GC_IMPLEMENTATION_G1_G1COLLECTORPOLICY_HPP
#define SHARE_VM_GC_IMPLEMENTATION_G1_G1COLLECTORPOLICY_HPP

#include "gc_implementation/g1/collectionSetChooser.hpp"
#include "gc_implementation/g1/g1MMUTracker.hpp"
#include "memory/collectorPolicy.hpp"

// A G1CollectorPolicy makes policy decisions that determine the
// characteristics of the collector.  Examples include:
//   * choice of collection set.
//   * when to collect.

class HeapRegion;
class CollectionSetChooser;

// Yes, this is a bit unpleasant... but it saves replicating the same thing
// over and over again and introducing subtle problems through small typos and
// cutting and pasting mistakes. The macros below introduces a number
// sequnce into the following two classes and the methods that access it.

#define define_num_seq(name)                                                  \
private:                                                                      \
  NumberSeq _all_##name##_times_ms;                                           \
public:                                                                       \
  void record_##name##_time_ms(double ms) {                                   \
    _all_##name##_times_ms.add(ms);                                           \
  }                                                                           \
  NumberSeq* get_##name##_seq() {                                             \
    return &_all_##name##_times_ms;                                           \
  }

class MainBodySummary;

class PauseSummary: public CHeapObj {
  define_num_seq(total)
    define_num_seq(other)

public:
  virtual MainBodySummary*    main_body_summary()    { return NULL; }
};

class MainBodySummary: public CHeapObj {
  define_num_seq(satb_drain) // optional
  define_num_seq(parallel) // parallel only
    define_num_seq(ext_root_scan)
    define_num_seq(mark_stack_scan)
    define_num_seq(update_rs)
    define_num_seq(scan_rs)
    define_num_seq(obj_copy)
    define_num_seq(termination) // parallel only
    define_num_seq(parallel_other) // parallel only
  define_num_seq(mark_closure)
  define_num_seq(clear_ct)
};

class Summary: public PauseSummary,
               public MainBodySummary {
public:
  virtual MainBodySummary*    main_body_summary()    { return this; }
};

class G1CollectorPolicy: public CollectorPolicy {
private:
  // either equal to the number of parallel threads, if ParallelGCThreads
  // has been set, or 1 otherwise
  int _parallel_gc_threads;

  // The number of GC threads currently active.
  uintx _no_of_gc_threads;

  enum SomePrivateConstants {
    NumPrevPausesForHeuristics = 10
  };

  G1MMUTracker* _mmu_tracker;

  void initialize_flags();

  void initialize_all() {
    initialize_flags();
    initialize_size_info();
    initialize_perm_generation(PermGen::MarkSweepCompact);
  }

  CollectionSetChooser* _collectionSetChooser;

  double _cur_collection_start_sec;
  size_t _cur_collection_pause_used_at_start_bytes;
  size_t _cur_collection_pause_used_regions_at_start;
  size_t _prev_collection_pause_used_at_end_bytes;
  double _cur_collection_par_time_ms;
  double _cur_satb_drain_time_ms;
  double _cur_clear_ct_time_ms;
  double _cur_ref_proc_time_ms;
  double _cur_ref_enq_time_ms;

#ifndef PRODUCT
  // Card Table Count Cache stats
  double _min_clear_cc_time_ms;         // min
  double _max_clear_cc_time_ms;         // max
  double _cur_clear_cc_time_ms;         // clearing time during current pause
  double _cum_clear_cc_time_ms;         // cummulative clearing time
  jlong  _num_cc_clears;                // number of times the card count cache has been cleared
#endif

  // These exclude marking times.
  TruncatedSeq* _recent_gc_times_ms;

  TruncatedSeq* _concurrent_mark_remark_times_ms;
  TruncatedSeq* _concurrent_mark_cleanup_times_ms;

  Summary*           _summary;

  NumberSeq* _all_pause_times_ms;
  NumberSeq* _all_full_gc_times_ms;
  double _stop_world_start;
  NumberSeq* _all_stop_world_times_ms;
  NumberSeq* _all_yield_times_ms;

  int        _aux_num;
  NumberSeq* _all_aux_times_ms;
  double*    _cur_aux_start_times_ms;
  double*    _cur_aux_times_ms;
  bool*      _cur_aux_times_set;

  double* _par_last_gc_worker_start_times_ms;
  double* _par_last_ext_root_scan_times_ms;
  double* _par_last_mark_stack_scan_times_ms;
  double* _par_last_update_rs_times_ms;
  double* _par_last_update_rs_processed_buffers;
  double* _par_last_scan_rs_times_ms;
  double* _par_last_obj_copy_times_ms;
  double* _par_last_termination_times_ms;
  double* _par_last_termination_attempts;
  double* _par_last_gc_worker_end_times_ms;
  double* _par_last_gc_worker_times_ms;

  // Each workers 'other' time i.e. the elapsed time of the parallel
  // phase of the pause minus the sum of the individual sub-phase
  // times for a given worker thread.
  double* _par_last_gc_worker_other_times_ms;

  // indicates whether we are in full young or partially young GC mode
  bool _full_young_gcs;

  // if true, then it tries to dynamically adjust the length of the
  // young list
  bool _adaptive_young_list_length;
  size_t _young_list_target_length;
  size_t _young_list_fixed_length;
  size_t _prev_eden_capacity; // used for logging

  // The max number of regions we can extend the eden by while the GC
  // locker is active. This should be >= _young_list_target_length;
  size_t _young_list_max_length;

  bool   _last_young_gc_full;

  unsigned              _full_young_pause_num;
  unsigned              _partial_young_pause_num;

  bool                  _during_marking;
  bool                  _in_marking_window;
  bool                  _in_marking_window_im;

  SurvRateGroup*        _short_lived_surv_rate_group;
  SurvRateGroup*        _survivor_surv_rate_group;
  // add here any more surv rate groups

  double                _gc_overhead_perc;

  double _reserve_factor;
  size_t _reserve_regions;

  bool during_marking() {
    return _during_marking;
  }

private:
  enum PredictionConstants {
    TruncatedSeqLength = 10
  };

  TruncatedSeq* _alloc_rate_ms_seq;
  double        _prev_collection_pause_end_ms;

  TruncatedSeq* _pending_card_diff_seq;
  TruncatedSeq* _rs_length_diff_seq;
  TruncatedSeq* _cost_per_card_ms_seq;
  TruncatedSeq* _fully_young_cards_per_entry_ratio_seq;
  TruncatedSeq* _partially_young_cards_per_entry_ratio_seq;
  TruncatedSeq* _cost_per_entry_ms_seq;
  TruncatedSeq* _partially_young_cost_per_entry_ms_seq;
  TruncatedSeq* _cost_per_byte_ms_seq;
  TruncatedSeq* _constant_other_time_ms_seq;
  TruncatedSeq* _young_other_cost_per_region_ms_seq;
  TruncatedSeq* _non_young_other_cost_per_region_ms_seq;

  TruncatedSeq* _pending_cards_seq;
  TruncatedSeq* _rs_lengths_seq;

  TruncatedSeq* _cost_per_byte_ms_during_cm_seq;

  TruncatedSeq* _young_gc_eff_seq;

  bool   _using_new_ratio_calculations;
  size_t _min_desired_young_length; // as set on the command line or default calculations
  size_t _max_desired_young_length; // as set on the command line or default calculations

  size_t _eden_cset_region_length;
  size_t _survivor_cset_region_length;
  size_t _old_cset_region_length;

  void init_cset_region_lengths(size_t eden_cset_region_length,
                                size_t survivor_cset_region_length);

  size_t eden_cset_region_length()     { return _eden_cset_region_length;     }
  size_t survivor_cset_region_length() { return _survivor_cset_region_length; }
  size_t old_cset_region_length()      { return _old_cset_region_length;      }

  size_t _free_regions_at_end_of_collection;

  size_t _recorded_rs_lengths;
  size_t _max_rs_lengths;

  double _recorded_young_free_cset_time_ms;
  double _recorded_non_young_free_cset_time_ms;

  double _sigma;
  double _expensive_region_limit_ms;

  size_t _rs_lengths_prediction;

  size_t _known_garbage_bytes;
  double _known_garbage_ratio;

  double sigma() {
    return _sigma;
  }

  // A function that prevents us putting too much stock in small sample
  // sets.  Returns a number between 2.0 and 1.0, depending on the number
  // of samples.  5 or more samples yields one; fewer scales linearly from
  // 2.0 at 1 sample to 1.0 at 5.
  double confidence_factor(int samples) {
    if (samples > 4) return 1.0;
    else return  1.0 + sigma() * ((double)(5 - samples))/2.0;
  }

  double get_new_neg_prediction(TruncatedSeq* seq) {
    return seq->davg() - sigma() * seq->dsd();
  }

#ifndef PRODUCT
  bool verify_young_ages(HeapRegion* head, SurvRateGroup *surv_rate_group);
#endif // PRODUCT

  void adjust_concurrent_refinement(double update_rs_time,
                                    double update_rs_processed_buffers,
                                    double goal_ms);

  uintx no_of_gc_threads() { return _no_of_gc_threads; }
  void set_no_of_gc_threads(uintx v) { _no_of_gc_threads = v; }

  double _pause_time_target_ms;
  double _recorded_young_cset_choice_time_ms;
  double _recorded_non_young_cset_choice_time_ms;
  size_t _pending_cards;
  size_t _max_pending_cards;

public:
  // Accessors

  void set_region_eden(HeapRegion* hr, int young_index_in_cset) {
    hr->set_young();
    hr->install_surv_rate_group(_short_lived_surv_rate_group);
    hr->set_young_index_in_cset(young_index_in_cset);
  }

  void set_region_survivor(HeapRegion* hr, int young_index_in_cset) {
    assert(hr->is_young() && hr->is_survivor(), "pre-condition");
    hr->install_surv_rate_group(_survivor_surv_rate_group);
    hr->set_young_index_in_cset(young_index_in_cset);
  }

#ifndef PRODUCT
  bool verify_young_ages();
#endif // PRODUCT

  double get_new_prediction(TruncatedSeq* seq) {
    return MAX2(seq->davg() + sigma() * seq->dsd(),
                seq->davg() * confidence_factor(seq->num()));
  }

  void record_max_rs_lengths(size_t rs_lengths) {
    _max_rs_lengths = rs_lengths;
  }

  size_t predict_pending_card_diff() {
    double prediction = get_new_neg_prediction(_pending_card_diff_seq);
    if (prediction < 0.00001)
      return 0;
    else
      return (size_t) prediction;
  }

  size_t predict_pending_cards() {
    size_t max_pending_card_num = _g1->max_pending_card_num();
    size_t diff = predict_pending_card_diff();
    size_t prediction;
    if (diff > max_pending_card_num)
      prediction = max_pending_card_num;
    else
      prediction = max_pending_card_num - diff;

    return prediction;
  }

  size_t predict_rs_length_diff() {
    return (size_t) get_new_prediction(_rs_length_diff_seq);
  }

  double predict_alloc_rate_ms() {
    return get_new_prediction(_alloc_rate_ms_seq);
  }

  double predict_cost_per_card_ms() {
    return get_new_prediction(_cost_per_card_ms_seq);
  }

  double predict_rs_update_time_ms(size_t pending_cards) {
    return (double) pending_cards * predict_cost_per_card_ms();
  }

  double predict_fully_young_cards_per_entry_ratio() {
    return get_new_prediction(_fully_young_cards_per_entry_ratio_seq);
  }

  double predict_partially_young_cards_per_entry_ratio() {
    if (_partially_young_cards_per_entry_ratio_seq->num() < 2)
      return predict_fully_young_cards_per_entry_ratio();
    else
      return get_new_prediction(_partially_young_cards_per_entry_ratio_seq);
  }

  size_t predict_young_card_num(size_t rs_length) {
    return (size_t) ((double) rs_length *
                     predict_fully_young_cards_per_entry_ratio());
  }

  size_t predict_non_young_card_num(size_t rs_length) {
    return (size_t) ((double) rs_length *
                     predict_partially_young_cards_per_entry_ratio());
  }

  double predict_rs_scan_time_ms(size_t card_num) {
    if (full_young_gcs())
      return (double) card_num * get_new_prediction(_cost_per_entry_ms_seq);
    else
      return predict_partially_young_rs_scan_time_ms(card_num);
  }

  double predict_partially_young_rs_scan_time_ms(size_t card_num) {
    if (_partially_young_cost_per_entry_ms_seq->num() < 3)
      return (double) card_num * get_new_prediction(_cost_per_entry_ms_seq);
    else
      return (double) card_num *
        get_new_prediction(_partially_young_cost_per_entry_ms_seq);
  }

  double predict_object_copy_time_ms_during_cm(size_t bytes_to_copy) {
    if (_cost_per_byte_ms_during_cm_seq->num() < 3)
      return 1.1 * (double) bytes_to_copy *
        get_new_prediction(_cost_per_byte_ms_seq);
    else
      return (double) bytes_to_copy *
        get_new_prediction(_cost_per_byte_ms_during_cm_seq);
  }

  double predict_object_copy_time_ms(size_t bytes_to_copy) {
    if (_in_marking_window && !_in_marking_window_im)
      return predict_object_copy_time_ms_during_cm(bytes_to_copy);
    else
      return (double) bytes_to_copy *
        get_new_prediction(_cost_per_byte_ms_seq);
  }

  double predict_constant_other_time_ms() {
    return get_new_prediction(_constant_other_time_ms_seq);
  }

  double predict_young_other_time_ms(size_t young_num) {
    return
      (double) young_num *
      get_new_prediction(_young_other_cost_per_region_ms_seq);
  }

  double predict_non_young_other_time_ms(size_t non_young_num) {
    return
      (double) non_young_num *
      get_new_prediction(_non_young_other_cost_per_region_ms_seq);
  }

  void check_if_region_is_too_expensive(double predicted_time_ms);

  double predict_young_collection_elapsed_time_ms(size_t adjustment);
  double predict_base_elapsed_time_ms(size_t pending_cards);
  double predict_base_elapsed_time_ms(size_t pending_cards,
                                      size_t scanned_cards);
  size_t predict_bytes_to_copy(HeapRegion* hr);
  double predict_region_elapsed_time_ms(HeapRegion* hr, bool young);

  void set_recorded_rs_lengths(size_t rs_lengths);

  size_t cset_region_length()       { return young_cset_region_length() +
                                             old_cset_region_length(); }
  size_t young_cset_region_length() { return eden_cset_region_length() +
                                             survivor_cset_region_length(); }

  void record_young_free_cset_time_ms(double time_ms) {
    _recorded_young_free_cset_time_ms = time_ms;
  }

  void record_non_young_free_cset_time_ms(double time_ms) {
    _recorded_non_young_free_cset_time_ms = time_ms;
  }

  double predict_young_gc_eff() {
    return get_new_neg_prediction(_young_gc_eff_seq);
  }

  double predict_survivor_regions_evac_time();

  void cset_regions_freed() {
    bool propagate = _last_young_gc_full && !_in_marking_window;
    _short_lived_surv_rate_group->all_surviving_words_recorded(propagate);
    _survivor_surv_rate_group->all_surviving_words_recorded(propagate);
    // also call it on any more surv rate groups
  }

  void set_known_garbage_bytes(size_t known_garbage_bytes) {
    _known_garbage_bytes = known_garbage_bytes;
    size_t heap_bytes = _g1->capacity();
    _known_garbage_ratio = (double) _known_garbage_bytes / (double) heap_bytes;
  }

  void decrease_known_garbage_bytes(size_t known_garbage_bytes) {
    guarantee( _known_garbage_bytes >= known_garbage_bytes, "invariant" );

    _known_garbage_bytes -= known_garbage_bytes;
    size_t heap_bytes = _g1->capacity();
    _known_garbage_ratio = (double) _known_garbage_bytes / (double) heap_bytes;
  }

  G1MMUTracker* mmu_tracker() {
    return _mmu_tracker;
  }

  double max_pause_time_ms() {
    return _mmu_tracker->max_gc_time() * 1000.0;
  }

  double predict_remark_time_ms() {
    return get_new_prediction(_concurrent_mark_remark_times_ms);
  }

  double predict_cleanup_time_ms() {
    return get_new_prediction(_concurrent_mark_cleanup_times_ms);
  }

  // Returns an estimate of the survival rate of the region at yg-age
  // "yg_age".
  double predict_yg_surv_rate(int age, SurvRateGroup* surv_rate_group) {
    TruncatedSeq* seq = surv_rate_group->get_seq(age);
    if (seq->num() == 0)
      gclog_or_tty->print("BARF! age is %d", age);
    guarantee( seq->num() > 0, "invariant" );
    double pred = get_new_prediction(seq);
    if (pred > 1.0)
      pred = 1.0;
    return pred;
  }

  double predict_yg_surv_rate(int age) {
    return predict_yg_surv_rate(age, _short_lived_surv_rate_group);
  }

  double accum_yg_surv_rate_pred(int age) {
    return _short_lived_surv_rate_group->accum_surv_rate_pred(age);
  }

private:
  void print_stats(int level, const char* str, double value);
  void print_stats(int level, const char* str, int value);

  void print_par_stats(int level, const char* str, double* data);
  void print_par_sizes(int level, const char* str, double* data);

  void check_other_times(int level,
                         NumberSeq* other_times_ms,
                         NumberSeq* calc_other_times_ms) const;

  void print_summary (PauseSummary* stats) const;

  void print_summary (int level, const char* str, NumberSeq* seq) const;
  void print_summary_sd (int level, const char* str, NumberSeq* seq) const;

  double avg_value (double* data);
  double max_value (double* data);
  double sum_of_values (double* data);
  double max_sum (double* data1, double* data2);

  double _last_pause_time_ms;

  size_t _bytes_in_collection_set_before_gc;
  size_t _bytes_copied_during_gc;

  // Used to count used bytes in CS.
  friend class CountCSClosure;

  // Statistics kept per GC stoppage, pause or full.
  TruncatedSeq* _recent_prev_end_times_for_all_gcs_sec;

  // Add a new GC of the given duration and end time to the record.
  void update_recent_gc_times(double end_time_sec, double elapsed_ms);

  // The head of the list (via "next_in_collection_set()") representing the
  // current collection set. Set from the incrementally built collection
  // set at the start of the pause.
  HeapRegion* _collection_set;

  // The number of bytes in the collection set before the pause. Set from
  // the incrementally built collection set at the start of an evacuation
  // pause.
  size_t _collection_set_bytes_used_before;

  // The associated information that is maintained while the incremental
  // collection set is being built with young regions. Used to populate
  // the recorded info for the evacuation pause.

  enum CSetBuildType {
    Active,             // We are actively building the collection set
    Inactive            // We are not actively building the collection set
  };

  CSetBuildType _inc_cset_build_state;

  // The head of the incrementally built collection set.
  HeapRegion* _inc_cset_head;

  // The tail of the incrementally built collection set.
  HeapRegion* _inc_cset_tail;

  // The number of bytes in the incrementally built collection set.
  // Used to set _collection_set_bytes_used_before at the start of
  // an evacuation pause.
  size_t _inc_cset_bytes_used_before;

  // Used to record the highest end of heap region in collection set
  HeapWord* _inc_cset_max_finger;

  // The RSet lengths recorded for regions in the collection set
  // (updated by the periodic sampling of the regions in the
  // young list/collection set).
  size_t _inc_cset_recorded_rs_lengths;

  // The predicted elapsed time it will take to collect the regions
  // in the collection set (updated by the periodic sampling of the
  // regions in the young list/collection set).
  double _inc_cset_predicted_elapsed_time_ms;

  // Stash a pointer to the g1 heap.
  G1CollectedHeap* _g1;

  // The ratio of gc time to elapsed time, computed over recent pauses.
  double _recent_avg_pause_time_ratio;

  double recent_avg_pause_time_ratio() {
    return _recent_avg_pause_time_ratio;
  }

  // At the end of a pause we check the heap occupancy and we decide
  // whether we will start a marking cycle during the next pause. If
  // we decide that we want to do that, we will set this parameter to
  // true. So, this parameter will stay true between the end of a
  // pause and the beginning of a subsequent pause (not necessarily
  // the next one, see the comments on the next field) when we decide
  // that we will indeed start a marking cycle and do the initial-mark
  // work.
  volatile bool _initiate_conc_mark_if_possible;

  // If initiate_conc_mark_if_possible() is set at the beginning of a
  // pause, it is a suggestion that the pause should start a marking
  // cycle by doing the initial-mark work. However, it is possible
  // that the concurrent marking thread is still finishing up the
  // previous marking cycle (e.g., clearing the next marking
  // bitmap). If that is the case we cannot start a new cycle and
  // we'll have to wait for the concurrent marking thread to finish
  // what it is doing. In this case we will postpone the marking cycle
  // initiation decision for the next pause. When we eventually decide
  // to start a cycle, we will set _during_initial_mark_pause which
  // will stay true until the end of the initial-mark pause and it's
  // the condition that indicates that a pause is doing the
  // initial-mark work.
  volatile bool _during_initial_mark_pause;

  bool _should_revert_to_full_young_gcs;
  bool _last_full_young_gc;

  // This set of variables tracks the collector efficiency, in order to
  // determine whether we should initiate a new marking.
  double _cur_mark_stop_world_time_ms;
  double _mark_remark_start_sec;
  double _mark_cleanup_start_sec;
  double _mark_closure_time_ms;

  // Update the young list target length either by setting it to the
  // desired fixed value or by calculating it using G1's pause
  // prediction model. If no rs_lengths parameter is passed, predict
  // the RS lengths using the prediction model, otherwise use the
  // given rs_lengths as the prediction.
  void update_young_list_target_length(size_t rs_lengths = (size_t) -1);

  // Calculate and return the minimum desired young list target
  // length. This is the minimum desired young list length according
  // to the user's inputs.
  size_t calculate_young_list_desired_min_length(size_t base_min_length);

  // Calculate and return the maximum desired young list target
  // length. This is the maximum desired young list length according
  // to the user's inputs.
  size_t calculate_young_list_desired_max_length();

  // Calculate and return the maximum young list target length that
  // can fit into the pause time goal. The parameters are: rs_lengths
  // represent the prediction of how large the young RSet lengths will
  // be, base_min_length is the alreay existing number of regions in
  // the young list, min_length and max_length are the desired min and
  // max young list length according to the user's inputs.
  size_t calculate_young_list_target_length(size_t rs_lengths,
                                            size_t base_min_length,
                                            size_t desired_min_length,
                                            size_t desired_max_length);

  // Check whether a given young length (young_length) fits into the
  // given target pause time and whether the prediction for the amount
  // of objects to be copied for the given length will fit into the
  // given free space (expressed by base_free_regions).  It is used by
  // calculate_young_list_target_length().
  bool predict_will_fit(size_t young_length, double base_time_ms,
                        size_t base_free_regions, double target_pause_time_ms);

  // Count the number of bytes used in the CS.
  void count_CS_bytes_used();

  void update_young_list_size_using_newratio(size_t number_of_heap_regions);

public:

  G1CollectorPolicy();

  virtual G1CollectorPolicy* as_g1_policy() { return this; }

  virtual CollectorPolicy::Name kind() {
    return CollectorPolicy::G1CollectorPolicyKind;
  }

  // Check the current value of the young list RSet lengths and
  // compare it against the last prediction. If the current value is
  // higher, recalculate the young list target length prediction.
  void revise_young_list_target_length_if_necessary();

  size_t bytes_in_collection_set() {
    return _bytes_in_collection_set_before_gc;
  }

  unsigned calc_gc_alloc_time_stamp() {
    return _all_pause_times_ms->num() + 1;
  }

  // This should be called after the heap is resized.
  void record_new_heap_size(size_t new_number_of_regions);

public:

  void init();

  // Create jstat counters for the policy.
  virtual void initialize_gc_policy_counters();

  virtual HeapWord* mem_allocate_work(size_t size,
                                      bool is_tlab,
                                      bool* gc_overhead_limit_was_exceeded);

  // This method controls how a collector handles one or more
  // of its generations being fully allocated.
  virtual HeapWord* satisfy_failed_allocation(size_t size,
                                              bool is_tlab);

  BarrierSet::Name barrier_set_name() { return BarrierSet::G1SATBCTLogging; }

  GenRemSet::Name  rem_set_name()     { return GenRemSet::CardTable; }

  // Update the heuristic info to record a collection pause of the given
  // start time, where the given number of bytes were used at the start.
  // This may involve changing the desired size of a collection set.

  void record_stop_world_start();

  void record_collection_pause_start(double start_time_sec, size_t start_used);

  // Must currently be called while the world is stopped.
  void record_concurrent_mark_init_end(double
                                           mark_init_elapsed_time_ms);

  void record_mark_closure_time(double mark_closure_time_ms) {
    _mark_closure_time_ms = mark_closure_time_ms;
  }

  void record_concurrent_mark_remark_start();
  void record_concurrent_mark_remark_end();

  void record_concurrent_mark_cleanup_start();
  void record_concurrent_mark_cleanup_end(int no_of_gc_threads);
  void record_concurrent_mark_cleanup_completed();

  void record_concurrent_pause();
  void record_concurrent_pause_end();

  void record_collection_pause_end(int no_of_gc_threads);
  void print_heap_transition();

  // Record the fact that a full collection occurred.
  void record_full_collection_start();
  void record_full_collection_end();

  void record_gc_worker_start_time(int worker_i, double ms) {
    _par_last_gc_worker_start_times_ms[worker_i] = ms;
  }

  void record_ext_root_scan_time(int worker_i, double ms) {
    _par_last_ext_root_scan_times_ms[worker_i] = ms;
  }

  void record_mark_stack_scan_time(int worker_i, double ms) {
    _par_last_mark_stack_scan_times_ms[worker_i] = ms;
  }

  void record_satb_drain_time(double ms) {
    assert(_g1->mark_in_progress(), "shouldn't be here otherwise");
    _cur_satb_drain_time_ms = ms;
  }

  void record_update_rs_time(int thread, double ms) {
    _par_last_update_rs_times_ms[thread] = ms;
  }

  void record_update_rs_processed_buffers (int thread,
                                           double processed_buffers) {
    _par_last_update_rs_processed_buffers[thread] = processed_buffers;
  }

  void record_scan_rs_time(int thread, double ms) {
    _par_last_scan_rs_times_ms[thread] = ms;
  }

  void reset_obj_copy_time(int thread) {
    _par_last_obj_copy_times_ms[thread] = 0.0;
  }

  void reset_obj_copy_time() {
    reset_obj_copy_time(0);
  }

  void record_obj_copy_time(int thread, double ms) {
    _par_last_obj_copy_times_ms[thread] += ms;
  }

  void record_termination(int thread, double ms, size_t attempts) {
    _par_last_termination_times_ms[thread] = ms;
    _par_last_termination_attempts[thread] = (double) attempts;
  }

  void record_gc_worker_end_time(int worker_i, double ms) {
    _par_last_gc_worker_end_times_ms[worker_i] = ms;
  }

  void record_pause_time_ms(double ms) {
    _last_pause_time_ms = ms;
  }

  void record_clear_ct_time(double ms) {
    _cur_clear_ct_time_ms = ms;
  }

  void record_par_time(double ms) {
    _cur_collection_par_time_ms = ms;
  }

  void record_aux_start_time(int i) {
    guarantee(i < _aux_num, "should be within range");
    _cur_aux_start_times_ms[i] = os::elapsedTime() * 1000.0;
  }

  void record_aux_end_time(int i) {
    guarantee(i < _aux_num, "should be within range");
    double ms = os::elapsedTime() * 1000.0 - _cur_aux_start_times_ms[i];
    _cur_aux_times_set[i] = true;
    _cur_aux_times_ms[i] += ms;
  }

  void record_ref_proc_time(double ms) {
    _cur_ref_proc_time_ms = ms;
  }

  void record_ref_enq_time(double ms) {
    _cur_ref_enq_time_ms = ms;
  }

#ifndef PRODUCT
  void record_cc_clear_time(double ms) {
    if (_min_clear_cc_time_ms < 0.0 || ms <= _min_clear_cc_time_ms)
      _min_clear_cc_time_ms = ms;
    if (_max_clear_cc_time_ms < 0.0 || ms >= _max_clear_cc_time_ms)
      _max_clear_cc_time_ms = ms;
    _cur_clear_cc_time_ms = ms;
    _cum_clear_cc_time_ms += ms;
    _num_cc_clears++;
  }
#endif

  // Record how much space we copied during a GC. This is typically
  // called when a GC alloc region is being retired.
  void record_bytes_copied_during_gc(size_t bytes) {
    _bytes_copied_during_gc += bytes;
  }

  // The amount of space we copied during a GC.
  size_t bytes_copied_during_gc() {
    return _bytes_copied_during_gc;
  }

  // Choose a new collection set.  Marks the chosen regions as being
  // "in_collection_set", and links them together.  The head and number of
  // the collection set are available via access methods.
  void choose_collection_set(double target_pause_time_ms);

  // The head of the list (via "next_in_collection_set()") representing the
  // current collection set.
  HeapRegion* collection_set() { return _collection_set; }

  void clear_collection_set() { _collection_set = NULL; }

  // Add old region "hr" to the CSet.
  void add_old_region_to_cset(HeapRegion* hr);

  // Incremental CSet Support

  // The head of the incrementally built collection set.
  HeapRegion* inc_cset_head() { return _inc_cset_head; }

  // The tail of the incrementally built collection set.
  HeapRegion* inc_set_tail() { return _inc_cset_tail; }

  // Initialize incremental collection set info.
  void start_incremental_cset_building();

  void clear_incremental_cset() {
    _inc_cset_head = NULL;
    _inc_cset_tail = NULL;
  }

  // Stop adding regions to the incremental collection set
  void stop_incremental_cset_building() { _inc_cset_build_state = Inactive; }

  // Add/remove information about hr to the aggregated information
  // for the incrementally built collection set.
  void add_to_incremental_cset_info(HeapRegion* hr, size_t rs_length);
  void remove_from_incremental_cset_info(HeapRegion* hr);

  // Update information about hr in the aggregated information for
  // the incrementally built collection set.
  void update_incremental_cset_info(HeapRegion* hr, size_t new_rs_length);

private:
  // Update the incremental cset information when adding a region
  // (should not be called directly).
  void add_region_to_incremental_cset_common(HeapRegion* hr);

public:
  // Add hr to the LHS of the incremental collection set.
  void add_region_to_incremental_cset_lhs(HeapRegion* hr);

  // Add hr to the RHS of the incremental collection set.
  void add_region_to_incremental_cset_rhs(HeapRegion* hr);

#ifndef PRODUCT
  void print_collection_set(HeapRegion* list_head, outputStream* st);
#endif // !PRODUCT

  bool initiate_conc_mark_if_possible()       { return _initiate_conc_mark_if_possible;  }
  void set_initiate_conc_mark_if_possible()   { _initiate_conc_mark_if_possible = true;  }
  void clear_initiate_conc_mark_if_possible() { _initiate_conc_mark_if_possible = false; }

  bool during_initial_mark_pause()      { return _during_initial_mark_pause;  }
  void set_during_initial_mark_pause()  { _during_initial_mark_pause = true;  }
  void clear_during_initial_mark_pause(){ _during_initial_mark_pause = false; }

  // This sets the initiate_conc_mark_if_possible() flag to start a
  // new cycle, as long as we are not already in one. It's best if it
  // is called during a safepoint when the test whether a cycle is in
  // progress or not is stable.
  bool force_initial_mark_if_outside_cycle(GCCause::Cause gc_cause);

  // This is called at the very beginning of an evacuation pause (it
  // has to be the first thing that the pause does). If
  // initiate_conc_mark_if_possible() is true, and the concurrent
  // marking thread has completed its work during the previous cycle,
  // it will set during_initial_mark_pause() to so that the pause does
  // the initial-mark work and start a marking cycle.
  void decide_on_conc_mark_initiation();

  // If an expansion would be appropriate, because recent GC overhead had
  // exceeded the desired limit, return an amount to expand by.
  size_t expansion_amount();

#ifndef PRODUCT
  // Check any appropriate marked bytes info, asserting false if
  // something's wrong, else returning "true".
  bool assertMarkedBytesDataOK();
#endif

  // Print tracing information.
  void print_tracing_info() const;

  // Print stats on young survival ratio
  void print_yg_surv_rate_info() const;

  void finished_recalculating_age_indexes(bool is_survivors) {
    if (is_survivors) {
      _survivor_surv_rate_group->finished_recalculating_age_indexes();
    } else {
      _short_lived_surv_rate_group->finished_recalculating_age_indexes();
    }
    // do that for any other surv rate groups
  }

  bool is_young_list_full() {
    size_t young_list_length = _g1->young_list()->length();
    size_t young_list_target_length = _young_list_target_length;
    return young_list_length >= young_list_target_length;
  }

  bool can_expand_young_list() {
    size_t young_list_length = _g1->young_list()->length();
    size_t young_list_max_length = _young_list_max_length;
    return young_list_length < young_list_max_length;
  }

  size_t young_list_max_length() {
    return _young_list_max_length;
  }

  bool full_young_gcs() {
    return _full_young_gcs;
  }
  void set_full_young_gcs(bool full_young_gcs) {
    _full_young_gcs = full_young_gcs;
  }

  bool adaptive_young_list_length() {
    return _adaptive_young_list_length;
  }
  void set_adaptive_young_list_length(bool adaptive_young_list_length) {
    _adaptive_young_list_length = adaptive_young_list_length;
  }

  inline double get_gc_eff_factor() {
    double ratio = _known_garbage_ratio;

    double square = ratio * ratio;
    // square = square * square;
    double ret = square * 9.0 + 1.0;
#if 0
    gclog_or_tty->print_cr("ratio = %1.2lf, ret = %1.2lf", ratio, ret);
#endif // 0
    guarantee(0.0 <= ret && ret < 10.0, "invariant!");
    return ret;
  }

private:
  //
  // Survivor regions policy.
  //

  // Current tenuring threshold, set to 0 if the collector reaches the
  // maximum amount of suvivors regions.
  int _tenuring_threshold;

  // The limit on the number of regions allocated for survivors.
  size_t _max_survivor_regions;

  // For reporting purposes.
  size_t _eden_bytes_before_gc;
  size_t _survivor_bytes_before_gc;
  size_t _capacity_before_gc;

  // The amount of survor regions after a collection.
  size_t _recorded_survivor_regions;
  // List of survivor regions.
  HeapRegion* _recorded_survivor_head;
  HeapRegion* _recorded_survivor_tail;

  ageTable _survivors_age_table;

public:

  inline GCAllocPurpose
    evacuation_destination(HeapRegion* src_region, int age, size_t word_sz) {
      if (age < _tenuring_threshold && src_region->is_young()) {
        return GCAllocForSurvived;
      } else {
        return GCAllocForTenured;
      }
  }

  inline bool track_object_age(GCAllocPurpose purpose) {
    return purpose == GCAllocForSurvived;
  }

  static const size_t REGIONS_UNLIMITED = ~(size_t)0;

  size_t max_regions(int purpose);

  // The limit on regions for a particular purpose is reached.
  void note_alloc_region_limit_reached(int purpose) {
    if (purpose == GCAllocForSurvived) {
      _tenuring_threshold = 0;
    }
  }

  void note_start_adding_survivor_regions() {
    _survivor_surv_rate_group->start_adding_regions();
  }

  void note_stop_adding_survivor_regions() {
    _survivor_surv_rate_group->stop_adding_regions();
  }

  void record_survivor_regions(size_t      regions,
                               HeapRegion* head,
                               HeapRegion* tail) {
    _recorded_survivor_regions = regions;
    _recorded_survivor_head    = head;
    _recorded_survivor_tail    = tail;
  }

  size_t recorded_survivor_regions() {
    return _recorded_survivor_regions;
  }

  void record_thread_age_table(ageTable* age_table)
  {
    _survivors_age_table.merge_par(age_table);
  }

  void update_max_gc_locker_expansion();

  // Calculates survivor space parameters.
  void update_survivors_policy();

};

// This should move to some place more general...

// If we have "n" measurements, and we've kept track of their "sum" and the
// "sum_of_squares" of the measurements, this returns the variance of the
// sequence.
inline double variance(int n, double sum_of_squares, double sum) {
  double n_d = (double)n;
  double avg = sum/n_d;
  return (sum_of_squares - 2.0 * avg * sum + n_d * avg * avg) / n_d;
}

#endif // SHARE_VM_GC_IMPLEMENTATION_G1_G1COLLECTORPOLICY_HPP
