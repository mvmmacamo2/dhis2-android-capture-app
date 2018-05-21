package com.dhis2.data.forms;

import android.content.ContentValues;
import android.database.Cursor;
import android.support.annotation.NonNull;

import com.dhis2.data.tuples.Trio;
import com.dhis2.utils.CodeGenerator;
import com.dhis2.utils.DateUtils;
import com.google.android.gms.maps.model.LatLng;
import com.squareup.sqlbrite2.BriteDatabase;

import org.hisp.dhis.android.core.common.State;
import org.hisp.dhis.android.core.enrollment.EnrollmentModel;
import org.hisp.dhis.android.core.enrollment.EnrollmentStatus;
import org.hisp.dhis.android.core.event.EventModel;
import org.hisp.dhis.android.core.event.EventStatus;
import org.hisp.dhis.android.core.program.ProgramModel;
import org.hisp.dhis.android.core.program.ProgramStageModel;
import org.hisp.dhis.rules.RuleEngine;
import org.hisp.dhis.rules.RuleEngineContext;
import org.hisp.dhis.rules.RuleExpressionEvaluator;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.exceptions.OnErrorNotImplementedException;
import io.reactivex.functions.Consumer;
import timber.log.Timber;

@SuppressWarnings({
        "PMD.AvoidDuplicateLiterals"
})
class EnrollmentFormRepository implements FormRepository {
    private static final List<String> TITLE_TABLES = Arrays.asList(
            EnrollmentModel.TABLE, ProgramModel.TABLE);

    private static final String SELECT_TITLE = "SELECT Program.displayName\n" +
            "FROM Enrollment\n" +
            "  JOIN Program ON Enrollment.program = Program.uid\n" +
            "WHERE Enrollment.uid = ?";

    private static final String SELECT_ENROLLMENT_UID = "SELECT Enrollment.uid\n" +
            "FROM Enrollment\n" +
            "WHERE Enrollment.uid = ?";

    private static final String SELECT_ENROLLMENT_STATUS = "SELECT Enrollment.status\n" +
            "FROM Enrollment\n" +
            "WHERE Enrollment.uid = ?";

    private static final String SELECT_ENROLLMENT_DATE = "SELECT Enrollment.enrollmentDate\n" +
            "FROM Enrollment\n" +
            "WHERE Enrollment.uid = ?";

    private static final String SELECT_ENROLLMENT_PROGRAM = "SELECT Program.*\n" +
            "FROM Program JOIN Enrollment ON Enrollment.program = Program.uid\n" +
            "WHERE Enrollment.uid = ?";

    private static final String SELECT_AUTO_GENERATE_PROGRAM_STAGE = "SELECT ProgramStage.uid, " +
            "Program.uid, Enrollment.organisationUnit, ProgramStage.minDaysFromStart, ProgramStage.generatedByEnrollmentDate, Enrollment.incidentDate, Enrollment.enrollmentDate \n" +
            "FROM Enrollment\n" +
            "  JOIN Program ON Enrollment.program = Program.uid\n" +
            "  JOIN ProgramStage ON Program.uid = ProgramStage.program AND ProgramStage.autoGenerateEvent = 1\n" +
            "WHERE Enrollment.uid = ?";

    private static final String SELECT_USE_FIRST_STAGE = "SELECT ProgramStage.uid, ProgramStage.program, Enrollment.organisationUnit, Program.trackedEntityType \n" +
            "FROM Enrollment\n" +
            "  JOIN Program ON Enrollment.program = Program.uid\n" +
            "  JOIN ProgramStage ON Program.uid = ProgramStage.program AND Program.useFirstStageDuringRegistration  = 1\n" +
            "WHERE Enrollment.uid = ? AND ProgramStage.sortOrder = 1";

    private static final String SELECT_PROGRAM = "SELECT \n" +
            "  program\n" +
            "FROM Enrollment\n" +
            "WHERE uid = ?\n" +
            "LIMIT 1;";

    private static final String SELECT_TE_TYPE = "SELECT " +
            "Program.uid, Enrollment.trackedEntityInstance\n" +
            "FROM Program\n" +
            "JOIN Enrollment ON Enrollment.program = Program.uid\n" +
            "WHERE Enrollment.uid = ? LIMIT 1";


    @NonNull
    private final BriteDatabase briteDatabase;

    @NonNull
    private final CodeGenerator codeGenerator;

   /* @NonNull
    private final CurrentDateProvider currentDateProvider;*/

    @NonNull
    private final Flowable<RuleEngine> cachedRuleEngineFlowable;

    @NonNull
    private final String enrollmentUid;

    EnrollmentFormRepository(@NonNull BriteDatabase briteDatabase,
                             @NonNull RuleExpressionEvaluator expressionEvaluator,
                             @NonNull RulesRepository rulesRepository,
                             @NonNull CodeGenerator codeGenerator,
                             @NonNull String enrollmentUid) {
        this.briteDatabase = briteDatabase;
        this.codeGenerator = codeGenerator;
        this.enrollmentUid = enrollmentUid;

        // We don't want to rebuild RuleEngine on each request, since metadata of
        // the event is not changing throughout lifecycle of FormComponent.
        this.cachedRuleEngineFlowable = enrollmentProgram()
                .switchMap(program -> Flowable.zip(rulesRepository.rulesNew(program),
                        rulesRepository.ruleVariables(program), (rules, variables) ->
                                RuleEngineContext.builder(expressionEvaluator)
                                        .rules(rules)
                                        .ruleVariables(variables)
                                        .build().toEngineBuilder()
                                        .build()))
                .cacheWithInitialCapacity(1);
    }

    @NonNull
    @Override
    public Flowable<RuleEngine> ruleEngine() {
        return cachedRuleEngineFlowable;
    }

    @NonNull
    @Override
    public Flowable<String> title() {
        return briteDatabase
                .createQuery(TITLE_TABLES, SELECT_TITLE, enrollmentUid)
                .mapToOne(cursor -> cursor.getString(0)).toFlowable(BackpressureStrategy.LATEST)
                .distinctUntilChanged();
    }

    @NonNull
    @Override
    public Flowable<String> reportDate() {
        return briteDatabase
                .createQuery(EnrollmentModel.TABLE, SELECT_ENROLLMENT_DATE, enrollmentUid)
                .mapToOne(cursor -> cursor.getString(0) == null ? "" : cursor.getString(0)).toFlowable(BackpressureStrategy.LATEST)
                .distinctUntilChanged();
    }

    @NonNull
    @Override
    public Flowable<ProgramModel> incidentDate() {
        return briteDatabase.createQuery(ProgramModel.TABLE, SELECT_ENROLLMENT_PROGRAM, enrollmentUid)
                .mapToOne(ProgramModel::create)
                .toFlowable(BackpressureStrategy.LATEST)
                .distinctUntilChanged();
    }

    @Override
    public Flowable<ProgramModel> getAllowDatesInFuture() {
        return briteDatabase.createQuery(ProgramModel.TABLE, SELECT_ENROLLMENT_PROGRAM, enrollmentUid)
                .mapToOne(ProgramModel::create)
                .toFlowable(BackpressureStrategy.LATEST);
    }

    @NonNull
    @Override
    public Flowable<ReportStatus> reportStatus() {
        return briteDatabase
                .createQuery(EnrollmentModel.TABLE, SELECT_ENROLLMENT_STATUS, enrollmentUid)
                .mapToOne(cursor ->
                        ReportStatus.fromEnrollmentStatus(EnrollmentStatus.valueOf(cursor.getString(0)))).toFlowable(BackpressureStrategy.LATEST)
                .distinctUntilChanged();
    }

    @NonNull
    @Override
    public Flowable<List<FormSectionViewModel>> sections() {
        return briteDatabase
                .createQuery(EnrollmentModel.TABLE, SELECT_ENROLLMENT_UID, enrollmentUid)
                .mapToList(cursor -> FormSectionViewModel
                        .createForEnrollment(cursor.getString(0))).toFlowable(BackpressureStrategy.LATEST);
    }

    @NonNull
    @Override
    public Consumer<String> storeReportDate() {
        return reportDate -> {
            ContentValues enrollment = new ContentValues();
            enrollment.put(EnrollmentModel.Columns.DATE_OF_ENROLLMENT, reportDate);
            enrollment.put(EnrollmentModel.Columns.STATE, State.TO_UPDATE.name()); // TODO: Check if state is TO_POST
            // TODO: and if so, keep the TO_POST state
            briteDatabase.update(EnrollmentModel.TABLE, enrollment,
                    EnrollmentModel.Columns.UID + " = ?", enrollmentUid);
        };
    }

    @NonNull
    @Override
    public Consumer<LatLng> storeCoordinates() {
        return latLng -> {
            ContentValues enrollment = new ContentValues();
            enrollment.put(EnrollmentModel.Columns.LATITUDE, latLng.latitude);
            enrollment.put(EnrollmentModel.Columns.LONGITUDE, latLng.longitude); // TODO: Check if state is TO_POST
            // TODO: and if so, keep the TO_POST state
            briteDatabase.update(EnrollmentModel.TABLE, enrollment,
                    EnrollmentModel.Columns.UID + " = ?", enrollmentUid);
        };
    }

    @NonNull
    @Override
    public Consumer<String> storeIncidentDate() {
        return incidentDate -> {
            ContentValues enrollment = new ContentValues();
            enrollment.put(EnrollmentModel.Columns.DATE_OF_INCIDENT, incidentDate);
            enrollment.put(EnrollmentModel.Columns.STATE, State.TO_UPDATE.name()); // TODO: Check if state is TO_POST
            // TODO: and if so, keep the TO_POST state
            briteDatabase.update(EnrollmentModel.TABLE, enrollment,
                    EnrollmentModel.Columns.UID + " = ?", enrollmentUid);
        };
    }

    @NonNull
    @Override
    public Consumer<ReportStatus> storeReportStatus() {
        return reportStatus -> {
            ContentValues enrollment = new ContentValues();
            enrollment.put(EnrollmentModel.Columns.ENROLLMENT_STATUS,
                    ReportStatus.toEnrollmentStatus(reportStatus).name());
            enrollment.put(EnrollmentModel.Columns.STATE, State.TO_UPDATE.name()); // TODO: Check if state is TO_POST
            // TODO: and if so, keep the TO_POST state
            briteDatabase.update(EnrollmentModel.TABLE, enrollment,
                    EnrollmentModel.Columns.UID + " = ?", enrollmentUid);
        };
    }

    @NonNull
    @Override
    public Consumer<String> autoGenerateEvent() {
        return enrollmentUid -> {
            Cursor cursor = briteDatabase.query(SELECT_AUTO_GENERATE_PROGRAM_STAGE, enrollmentUid);

            if (cursor != null) {
                cursor.moveToFirst();
                for (int i = 0; i < cursor.getCount(); i++) {

                    String programStage = cursor.getString(0);
                    String program = cursor.getString(1);
                    String orgUnit = cursor.getString(2);
                    int minDaysFromStart = cursor.getInt(3);
                    cursor.close();

                    Calendar cal = Calendar.getInstance();
                    cal.setTime(Calendar.getInstance().getTime());
                    cal.add(Calendar.DATE, minDaysFromStart);
                    Date eventDate = cal.getTime();

                    EventModel event = EventModel.builder()
                            .uid(codeGenerator.generate())
                            .created(Calendar.getInstance().getTime())
                            .lastUpdated(Calendar.getInstance().getTime())
                            .eventDate(eventDate)
                            .dueDate(eventDate)
                            .enrollmentUid(enrollmentUid)
                            .program(program)
                            .programStage(programStage)
                            .organisationUnit(orgUnit)
                            .status(EventStatus.SCHEDULE)
                            .state(State.TO_POST)
                            .build();

                    if (briteDatabase.insert(EventModel.TABLE, event.toContentValues()) < 0) {
                        throw new OnErrorNotImplementedException(new Throwable("Unable to store event:" + event));
                    }
                    cursor.moveToNext();
                }
            }
        };
    }

    @NonNull
    @Override
    public Observable<String> autoGenerateEvents(String enrollmentUid) {

        Cursor cursor = briteDatabase.query(SELECT_AUTO_GENERATE_PROGRAM_STAGE, enrollmentUid);

        if (cursor != null) {
            cursor.moveToFirst();
            for (int i = 0; i < cursor.getCount(); i++) {

                String programStage = cursor.getString(0);
                String program = cursor.getString(1);
                String orgUnit = cursor.getString(2);
                int minDaysFromStart = cursor.getInt(3);
                Boolean generatedByEnrollmentDate = cursor.getInt(4) == 1;
                Date incidentDate = null;
                Date enrollmentDate = null;

                try {
                    incidentDate = DateUtils.databaseDateFormat().parse(cursor.getString(5));
                    enrollmentDate = DateUtils.databaseDateFormat().parse(cursor.getString(6));

                } catch (Exception e) {
                    Timber.e(e);
                }


                Date eventDate;
                Calendar cal = Calendar.getInstance();
                if (generatedByEnrollmentDate) {
                    cal.setTime(enrollmentDate != null ? enrollmentDate : Calendar.getInstance().getTime());
                    cal.add(Calendar.DATE, minDaysFromStart);
                    eventDate = cal.getTime();
                } else {
                    cal.setTime(incidentDate != null ? incidentDate : Calendar.getInstance().getTime());
                    cal.add(Calendar.DATE, minDaysFromStart);
                    eventDate = cal.getTime();
                }

                EventModel event = EventModel.builder()
                        .uid(codeGenerator.generate())
                        .created(Calendar.getInstance().getTime())
                        .lastUpdated(Calendar.getInstance().getTime())
                        .eventDate(eventDate)
                        .dueDate(eventDate)
                        .enrollmentUid(enrollmentUid)
                        .program(program)
                        .programStage(programStage)
                        .organisationUnit(orgUnit)
                        .status(EventStatus.SCHEDULE)
                        .state(State.TO_POST)
                        .build();

                if (briteDatabase.insert(EventModel.TABLE, event.toContentValues()) < 0) {
                    throw new OnErrorNotImplementedException(new Throwable("Unable to store event:" + event));
                }
                cursor.moveToNext();
            }
            cursor.close();

        }

        return Observable.just(enrollmentUid);
    }

    @NonNull
    @Override
    public Observable<Trio<String, String, String>> useFirstStageDuringRegistration() {
        return briteDatabase.createQuery(ProgramStageModel.TABLE, SELECT_USE_FIRST_STAGE, enrollmentUid)
                .map(query -> {
                    Cursor cursor = query.run();
                    if (cursor != null && cursor.moveToFirst()) {
                        String programStageUid = cursor.getString(0);
                        String programStageProgram = cursor.getString(1);
                        String enrollmentOrgUnit = cursor.getString(2);
                        String trackedEntityType = cursor.getString(3);
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(Calendar.getInstance().getTime());
                        Date eventDate = cal.getTime();

                        EventModel event = EventModel.builder()
                                .uid(codeGenerator.generate())
                                .created(Calendar.getInstance().getTime())
                                .lastUpdated(Calendar.getInstance().getTime())
                                .eventDate(eventDate)
                                .enrollmentUid(enrollmentUid)
                                .program(programStageProgram)
                                .programStage(programStageUid)
                                .organisationUnit(enrollmentOrgUnit)
                                .status(EventStatus.ACTIVE)
                                .state(State.TO_POST)
                                .build();

                        if (briteDatabase.insert(EventModel.TABLE, event.toContentValues()) < 0) {
                            throw new OnErrorNotImplementedException(new Throwable("Unable to store event:" + event));
                        }
                        return Trio.create(enrollmentUid, trackedEntityType, event.uid());
                    } else {
                        Cursor tetCursor = briteDatabase.query(SELECT_TE_TYPE, enrollmentUid);
                        tetCursor.moveToFirst();

                        return Trio.create(tetCursor.getString(0), tetCursor.getString(1), "");
                    }
                });
    }

    @NonNull
    private Flowable<String> enrollmentProgram() {
        return briteDatabase.createQuery(EnrollmentModel.TABLE, SELECT_PROGRAM, enrollmentUid)
                .mapToOne(cursor -> cursor.getString(0)).toFlowable(BackpressureStrategy.LATEST);
    }
}